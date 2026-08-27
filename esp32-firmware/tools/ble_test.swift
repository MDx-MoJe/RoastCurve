// BLE 调试工具：连接 RoastBridge，dump GATT 表，测试订阅+两种写法
// 用法：swift ble_test.swift [withResponse|withoutResponse] [SSID 密码] [--subscribe]
import CoreBluetooth
import Foundation

let NUS_SERVICE = CBUUID(string: "6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
let NUS_TX = CBUUID(string: "6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

var creds = "TestWiFi\n88888888"
var useResponse = true
var doSubscribe = false
let args = CommandLine.arguments
if args.contains("--subscribe") { doSubscribe = true }
if args.contains("withoutResponse") { useResponse = false }
let posArgs = args.dropFirst().filter { $0 != "withResponse" && $0 != "withoutResponse" && $0 != "--subscribe" }
if posArgs.count >= 2 { creds = "\(posArgs[0])\n\(posArgs[1])" }

final class BLETest: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    var manager: CBCentralManager!
    var peripheral: CBPeripheral?
    var writeChar: CBCharacteristic?
    var notifyChar: CBCharacteristic?
    var wrote = false
    var doSubscribe = CommandLine.arguments.contains("--subscribe")

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn {
            print("蓝牙开启，扫描中（过滤 NUS 服务）...")
            central.scanForPeripherals(withServices: [NUS_SERVICE])
        } else {
            print("蓝牙状态异常: \(central.state.rawValue)")
            exit(1)
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover p: CBPeripheral,
                        advertisementData: [String: Any], rssi: NSNumber) {
        let name = p.name ?? (advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? "?")
        print("发现: \(name) RSSI=\(rssi)")
        if name.contains("Roast") {
            peripheral = p
            central.stopScan()
            print(">>> 连接 \(name) ...")
            central.connect(p)
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect p: CBPeripheral) {
        print(">>> 已连接")
        p.delegate = self
        p.discoverServices([NUS_SERVICE])
    }

    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        for s in p.services ?? [] {
            print("服务: \(s.uuid)")
            p.discoverCharacteristics(nil, for: s)
        }
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor s: CBService, error: Error?) {
        for c in s.characteristics ?? [] {
            var names: [String] = []
            if c.properties.contains(.read) { names.append("read") }
            if c.properties.contains(.write) { names.append("write") }
            if c.properties.contains(.writeWithoutResponse) { names.append("writeNoResp") }
            if c.properties.contains(.notify) { names.append("notify") }
            print("  特征: \(c.uuid) [\(names.joined(separator: ","))]")
            if c.uuid == NUS_TX { writeChar = c }
            if c.properties.contains(.notify) { notifyChar = c }
        }
        if wrote { return }
        wrote = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            if self.doSubscribe, let n = self.notifyChar {
                print(">>> 订阅 NUS_RX 通知（复现 App 的 CCCD 写）...")
                self.peripheral?.setNotifyValue(true, for: n)
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { self.doWrite() }
            } else {
                self.doWrite()
            }
        }
    }

    func doWrite() {
        guard let c = writeChar, let p = peripheral else {
            print("!!! 没找到 NUS_TX 特征")
            exit(1)
        }
        let data = creds.data(using: .utf8)!
        let type: CBCharacteristicWriteType = useResponse ? .withResponse : .withoutResponse
        print(">>> 写入 \(data.count)字节 type=\(useResponse ? "withResponse" : "withoutResponse") subscribe=\(doSubscribe)")
        p.writeValue(data, for: c, type: type)
        if type == .withoutResponse {
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                print(">>> 无响应写已发出（无确认机制），2 秒后退出")
                exit(0)
            }
        }
    }

    func peripheral(_ p: CBPeripheral, didWriteValueFor c: CBCharacteristic, error: Error?) {
        if let e = error {
            print("<<< 板子确认: 失败 - \(e.localizedDescription)")
        } else {
            print("<<< 板子确认: 成功（板子收到了写请求）")
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { exit(0) }
    }
}

let test = BLETest()
test.manager = CBCentralManager(delegate: test, queue: nil)
RunLoop.main.run(until: Date(timeIntervalSinceNow: 40))
print("!!! 40 秒超时退出")
