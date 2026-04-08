package app.mylekha.client.flutter_usb_printer.adapter

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import android.util.Base64
import android.util.Log
import android.widget.Toast
import java.nio.charset.Charset
import java.util.*

data class PrinterConnection(
    val usbDevice: UsbDevice,
    var usbDeviceConnection: UsbDeviceConnection?,
    var usbInterface: UsbInterface?,
    var endPoint: UsbEndpoint?
)

class USBPrinterAdapter {

    private var mInstance: USBPrinterAdapter? = null

    private val LOG_TAG = "Flutter USB Printer"
    private var mContext: Context? = null
    private var mUSBManager: UsbManager? = null
    private var mPermissionIntent: PendingIntent? = null
    private var mUsbDevice: UsbDevice? = null
    private var mUsbDeviceConnection: UsbDeviceConnection? = null
    private var mUsbInterface: UsbInterface? = null
    private var mEndPoint: UsbEndpoint? = null

    private val ACTION_USB_PERMISSION = "app.mylekha.client.flutter_usb_printer.USB_PERMISSION"
    private val printerConnections: MutableMap<String, PrinterConnection> = mutableMapOf()
    private val discoveredPrinters = mutableMapOf<String, PrinterConnection>()

    fun getInstance(): USBPrinterAdapter? {
        if (mInstance == null) {
            mInstance = this
        }
        return mInstance
    }

    private fun getPrinterKey(vendorId: Int, productId: Int, serialNumber: String? = null): String {
        return if (serialNumber != null && serialNumber.isNotEmpty()) {
            "$vendorId:$productId:$serialNumber"
        } else {
            "$vendorId:$productId"
        }
    }

    private fun findPrinterKey(vendorId: Int, productId: Int): String? {
        val basicKey = getPrinterKey(vendorId, productId)
        
        // First check if we have an exact match with basic key
        if (printerConnections.containsKey(basicKey) || discoveredPrinters.containsKey(basicKey)) {
            return basicKey
        }
        
        // If not, search through all keys to find one that matches vendorId:productId
        val allKeys = (printerConnections.keys + discoveredPrinters.keys).toSet()
        return allKeys.find { key ->
            key.startsWith("$vendorId:$productId")
        } ?: basicKey // fallback to basic key
    }

    private val mUsbDeviceReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            val usbDevice = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return

            Log.i(LOG_TAG, "onReceive called with action: $action")

            // First check if we already have this printer in discoveredPrinters with a serial key
            // This preserves the serial that was set by getPrinterSerial
            val existingKey = findPrinterKey(usbDevice.vendorId, usbDevice.productId)

            val key = if (existingKey != null && existingKey.contains(":") && existingKey.split(":").size > 2) {
                // Use existing key with serial from discoveredPrinters
                Log.i(LOG_TAG, "Using existing key from discoveredPrinters: $existingKey")
                existingKey
            } else if (usbDevice.productName?.startsWith("POS Receipt Printer") == true) {
                // Use USB serial for POS Receipt Printers
                val usbSerial = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    usbDevice.serialNumber
                } else {
                    null
                }
                getPrinterKey(usbDevice.vendorId, usbDevice.productId, usbSerial)
            } else {
                // Use basic key for other printers (will be updated later)
                getPrinterKey(usbDevice.vendorId, usbDevice.productId)
            }


    Log.i(LOG_TAG, "Generated key: $key for device: ${usbDevice.productName}")
            
            if (ACTION_USB_PERMISSION == action) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    Log.i(LOG_TAG, "Permission granted for device: $key")
                    printerConnections[key] = PrinterConnection(usbDevice, null, null, null)

                       // Extract serial from the key  
                    val keyParts = key.split(":")
                    val serialNumber = if (keyParts.size > 2) keyParts[2] else null

                    Log.i(LOG_TAG, "Calling openConnection with serial: $serialNumber")
    
            val success = openConnection(usbDevice.vendorId, usbDevice.productId, serialNumber)
                                Log.i(LOG_TAG, "openConnection result: $success")

                } else {
                    Toast.makeText(context, "Permission denied for $key", Toast.LENGTH_LONG).show()
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED == action) {
                // Remove from both collections
                if (printerConnections.containsKey(key)) {
                    Toast.makeText(context, "USB device disconnected: $key", Toast.LENGTH_LONG).show()
                    closeConnection(key)
                    printerConnections.remove(key)
                }
                discoveredPrinters.remove(key)
                Log.i(LOG_TAG, "Removed device from discovered printers: $key")
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED == action) {
                // Add newly attached devices to discovered printers as PrinterConnection
                discoveredPrinters[key] = PrinterConnection(usbDevice, null, null, null)
                Log.i(LOG_TAG, "Added attached device to discovered printers: $key")
            }
        }
    }

    fun init(reactContext: Context?) {
        mContext = reactContext
        mUSBManager = mContext!!.getSystemService(Context.USB_SERVICE) as UsbManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            mPermissionIntent = PendingIntent.getBroadcast(
                mContext, 0, Intent(ACTION_USB_PERMISSION), 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
        } else {
            mPermissionIntent = PendingIntent.getBroadcast(
                mContext, 0, Intent(ACTION_USB_PERMISSION), 
                PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        mContext!!.registerReceiver(mUsbDeviceReceiver, filter)
        Log.v(LOG_TAG, "USB Printer initialized")
    }

    fun closeConnection(key: String) {
        val printer = printerConnections[key] ?: return
        printer.usbDeviceConnection?.releaseInterface(printer.usbInterface)
        printer.usbDeviceConnection?.close()
        printerConnections.remove(key)
    }

    fun closeConnectionIfExists() {
        if (mUsbDeviceConnection != null) {
            mUsbDeviceConnection!!.releaseInterface(mUsbInterface)
            mUsbDeviceConnection!!.close()
            mUsbInterface = null
            mEndPoint = null
            mUsbDeviceConnection = null
            mUsbDevice = null
        }
    }

    fun getDeviceList(): List<UsbDevice> {
        if (mUSBManager == null) {
            Toast.makeText(
                mContext,
                "USB Manager is not initialized while get device list",
                Toast.LENGTH_LONG
            ).show()
            return emptyList()
        }
        
        val devices = ArrayList(mUSBManager!!.deviceList.values)
        
        // Store discovered printers with appropriate keys based on printer type
        devices.forEach { device ->
            val key = if (device.productName?.startsWith("POS Receipt Printer") == true) {
                // Type A: Use USB serial immediately for POS Receipt Printers
                val usbSerial = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    device.serialNumber
                } else {
                    null
                }
                getPrinterKey(device.vendorId, device.productId, usbSerial)
            } else {
                // Type B: Basic key for now, will update later when ESC/POS serial is retrieved
                getPrinterKey(device.vendorId, device.productId)
            }
            
            discoveredPrinters[key] = PrinterConnection(device, null, null, null)
            Log.i(LOG_TAG, "Discovered printer: ${device.productName} with key: $key")
        }
        
        return devices
    }

    fun selectDevice(vendorId: Int, productId: Int, serialNumber: String?): Boolean {

            Log.i(LOG_TAG, "selectDevice called with: $vendorId:$productId:$serialNumber")

        val key = if (serialNumber != null && serialNumber.isNotEmpty()) {
            getPrinterKey(vendorId, productId, serialNumber)
        } else {
            findPrinterKey(vendorId, productId) ?: getPrinterKey(vendorId, productId)
        }

            Log.i(LOG_TAG, "selectDevice using key: $key")

    
        // Check if the printer is already connected
        if (printerConnections.containsKey(key)) {
            Log.v(LOG_TAG, "Printer already connected: $key")
            return true
        }

        // Find the desired USB device
        val usbDevices = getDeviceList()
        val selectedDevice = if (serialNumber != null && serialNumber.isNotEmpty()) {
            usbDevices.find { 
                it.vendorId == vendorId && 
                it.productId == productId &&
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    it.serialNumber == serialNumber
                } else {
                    false
                })
            }
        } else {
            usbDevices.find { it.vendorId == vendorId && it.productId == productId }
        }
        
        if (selectedDevice != null) {
            Log.v(LOG_TAG, "Requesting permission for device: $key")
            mUSBManager!!.requestPermission(selectedDevice, mPermissionIntent)
            return true
        }
        return false
    }

    fun openConnection(vendorId: Int, productId: Int, serialNumber: String?): Boolean {
    val key = if (serialNumber != null && serialNumber.isNotEmpty()) {
        getPrinterKey(vendorId, productId, serialNumber)  // Exact match
    } else {
        findPrinterKey(vendorId, productId) ?: return false  
    }
    
    val printer = printerConnections[key] ?: return false

    val usbInterface = printer.usbDevice.getInterface(0)
    for (i in 0 until usbInterface.endpointCount) {
        val ep = usbInterface.getEndpoint(i)
        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_OUT) {
            val usbDeviceConnection = mUSBManager!!.openDevice(printer.usbDevice)
            if (usbDeviceConnection != null && usbDeviceConnection.claimInterface(usbInterface, true)) {
                printerConnections[key] = printer.copy(
                    usbDeviceConnection = usbDeviceConnection,
                    usbInterface = usbInterface,
                    endPoint = ep
                )
                Toast.makeText(mContext, "Device connected: $key", Toast.LENGTH_SHORT).show()
                return true
            }
        }
    }
    return false
}


    // fun openConnection(vendorId: Int, productId: Int, serialNumber: String?): Boolean {
    //     val key = if (serialNumber != null && serialNumber.isNotEmpty()) {
    //         getPrinterKey(vendorId, productId, serialNumber)
    //     } else {
    //         findPrinterKey(vendorId, productId) ?: return false
    //     }
        
    //     val printer = printerConnections[key] ?: return false

    //     val usbInterface = printer.usbDevice.getInterface(0)
    //     for (i in 0 until usbInterface.endpointCount) {
    //         val ep = usbInterface.getEndpoint(i)
    //         if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_OUT) {
    //             val usbDeviceConnection = mUSBManager!!.openDevice(printer.usbDevice)
    //             if (usbDeviceConnection != null && usbDeviceConnection.claimInterface(usbInterface, true)) {
    //                 printerConnections[key] = printer.copy(
    //                     usbDeviceConnection = usbDeviceConnection,
    //                     usbInterface = usbInterface,
    //                     endPoint = ep
    //                 )
    //                 Toast.makeText(mContext, "Device connected: $key", Toast.LENGTH_SHORT).show()
    //                 return true
    //             }
    //         }
    //     }
    //     return false
    // }

    fun printText(vendorId: Int, productId: Int, serialNumber: String?, text: String): Boolean {
        val key = if (serialNumber != null && serialNumber.isNotEmpty()) {
            getPrinterKey(vendorId, productId, serialNumber)
        } else {
            findPrinterKey(vendorId, productId) ?: return false
        }
        
        val printer = printerConnections[key] ?: return false

        return if (openConnection(vendorId, productId, serialNumber)) {
            Thread {
                val bytes = text.toByteArray(Charset.forName("UTF-8"))
                val b = printer.usbDeviceConnection!!.bulkTransfer(printer.endPoint, bytes, bytes.size, 100000)
                Log.i(LOG_TAG, "Print status: $b")
            }.start()
            true
        } else {
            Log.e(LOG_TAG, "Failed to connect to device: $key")
            false
        }
    }

    fun printRawText(vendorId: Int, productId: Int, serialNumber: String?, data: String): Boolean {
        val key = if (serialNumber != null && serialNumber.isNotEmpty()) {
            getPrinterKey(vendorId, productId, serialNumber)
        } else {
            findPrinterKey(vendorId, productId) ?: return false
        }
        
        val printer = printerConnections[key] ?: return false

        return if (openConnection(vendorId, productId, serialNumber)) {
            Thread {
                val bytes = Base64.decode(data, Base64.DEFAULT)
                val status = printer.usbDeviceConnection!!.bulkTransfer(
                    printer.endPoint, bytes, bytes.size, 100000
                )
                Log.i(LOG_TAG, "PrintRawText Status: $status")
            }.start()
            true
        } else {
            Log.e(LOG_TAG, "Failed to connect to printer: $key")
            false
        }
    }

    fun write(vendorId: Int, productId: Int, serialNumber: String?, bytes: ByteArray): Boolean {
        val key = if (serialNumber != null && serialNumber.isNotEmpty()) {
            getPrinterKey(vendorId, productId, serialNumber)
        } else {
            findPrinterKey(vendorId, productId) ?: return false
        }

        Log.i(LOG_TAG, "Write called for key: $key")
    Log.i(LOG_TAG, "printerConnections keys: ${printerConnections.keys}")
    Log.i(LOG_TAG, "discoveredPrinters keys: ${discoveredPrinters.keys}")
    Log.i(LOG_TAG, "Key exists in printerConnections: ${printerConnections.containsKey(key)}")
    Log.i(LOG_TAG, "Key exists in discoveredPrinters: ${discoveredPrinters.containsKey(key)}")
    
   var printer = printerConnections[key]
  if (printer == null && discoveredPrinters.containsKey(key)) {
      val discovered = discoveredPrinters[key]
      if (discovered != null) {
          val usbInterface = discovered.usbDevice.getInterface(0)
          for (i in 0 until usbInterface.endpointCount) {
              val ep = usbInterface.getEndpoint(i)
              if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_OUT) {
                  val connection = mUSBManager?.openDevice(discovered.usbDevice)
                  if (connection != null && connection.claimInterface(usbInterface, true)) {
                      printer = PrinterConnection(
                          usbDevice = discovered.usbDevice,
                          usbDeviceConnection = connection,
                          usbInterface = usbInterface,
                          endPoint = ep
                      )
                      printerConnections[key] = printer!!
                      Log.i(LOG_TAG, "Auto-connected from discoveredPrinters for key: $key")
                      break
                  }
              }
          }
      }
  }
  if (printer == null) return false
    Log.i(LOG_TAG, "Printer connection details: connection=${printer.usbDeviceConnection}, endpoint=${printer.endPoint}")
        

        // Check if the printer is connected
        if (printer.usbDeviceConnection != null && printer.endPoint != null) {
            Thread {
                val status = printer.usbDeviceConnection!!.bulkTransfer(
                    printer.endPoint, bytes, bytes.size, 100000
                )
                Log.i(LOG_TAG, "Write Status: $status")

                if (status < 0) {
                    Log.e(LOG_TAG, "Failed to write to printer: $key")
                }
            }.start()
            return true
        } else {
            return false
        }
    }

    fun getPrinterSerial(vendorId: Int, productId: Int, serialNumber: String?, callback: (String?) -> Unit) {
        Log.i(LOG_TAG, "getPrinterSerial called for $vendorId:$productId with serial: $serialNumber")
        
        val key = if (serialNumber != null && serialNumber.isNotEmpty()) {
            // Use exact key when serial is provided
            getPrinterKey(vendorId, productId, serialNumber)
        } else {
            // Find any matching key when serial is not provided
            findPrinterKey(vendorId, productId)
        }
        
        if (key == null) {
            Log.e(LOG_TAG, "Printer not found: $vendorId:$productId:$serialNumber")
            callback(null)
            return
        }
        
        Log.i(LOG_TAG, "Using key: $key")
        
        // Find the printer (check both connected and discovered)
        val printer = printerConnections[key] ?: discoveredPrinters[key]
        if (printer == null) {
            Log.e(LOG_TAG, "Printer connection not found for key: $key")
            callback(null)
            return
        }
        
        // Check printer type and handle accordingly
        if (printer.usbDevice.productName?.startsWith("POS Receipt Printer") == true) {
            // Type A: POS Receipt Printer - use USB serial number
            Log.i(LOG_TAG, "POS Receipt Printer detected, using USB serial")
            val usbSerial = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                printer.usbDevice.serialNumber
            } else {
                null
            }
            
            if (usbSerial != null && usbSerial.isNotEmpty()) {
                Log.i(LOG_TAG, "USB Serial found: $usbSerial")
                callback(usbSerial)
            } else {
                Log.w(LOG_TAG, "No USB serial available for POS Receipt Printer")
                callback(null)
            }
        } else {
            // Type B: Other printers - use ESC/POS command
            Log.i(LOG_TAG, "Regular printer detected, using ESC/POS serial command")
            
            // Check if printer is already connected
            val connectedPrinter = printerConnections[key]
            if (connectedPrinter != null && connectedPrinter.usbDeviceConnection != null) {
                Log.i(LOG_TAG, "Using connected printer for ESC/POS serial")
                getESCPOSSerialFromConnectedPrinter(connectedPrinter, callback)
            } else {
                Log.i(LOG_TAG, "Using discovered printer for ESC/POS serial")
                getESCPOSSerialFromDiscoveredPrinter(printer, callback)
            }
        }
    }

    private fun getESCPOSSerialFromConnectedPrinter(printer: PrinterConnection, callback: (String?) -> Unit) {
        val serialCmd = byteArrayOf(29, 73, 68)
        Log.i(LOG_TAG, "Sending ESC/POS serial command to connected printer...")
        
        Thread {
            try {
                if (printer.usbDeviceConnection != null && printer.endPoint != null) {
                    val result = printer.usbDeviceConnection!!.bulkTransfer(
                        printer.endPoint, serialCmd, serialCmd.size, 3000
                    )
                    
                    if (result >= 0) {
                        Log.i(LOG_TAG, "ESC/POS serial command sent successfully via direct transfer")
                        Thread.sleep(500)
                        val response = readSyncLikeSDK(printer, 3000)
                        handleESCPOSSerialResponse(response, printer, callback)
                    } else {
                        Log.e(LOG_TAG, "Failed to send ESC/POS serial command via direct transfer")
                        callback(null)
                    }
                } else {
                    Log.e(LOG_TAG, "Printer connection or endpoint is null")
                    callback(null)
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error sending ESC/POS serial command", e)
                callback(null)
            }
        }.start()
    }

    private fun getESCPOSSerialFromDiscoveredPrinter(printer: PrinterConnection, callback: (String?) -> Unit) {
        Thread {
            var tempConnection: UsbDeviceConnection? = null
            var tempInterface: UsbInterface? = null
            var tempOutEndpoint: UsbEndpoint? = null
            
            try {
                if (mUSBManager == null) {
                    Log.e(LOG_TAG, "USB Manager not initialized")
                    callback(null)
                    return@Thread
                }
                
                if (!mUSBManager!!.hasPermission(printer.usbDevice)) {
                    Log.i(LOG_TAG, "No permission for discovered device")
                    callback(null)
                    return@Thread
                }
                
                tempConnection = mUSBManager!!.openDevice(printer.usbDevice)
                if (tempConnection == null) {
                    Log.e(LOG_TAG, "Failed to open temporary connection")
                    callback(null)
                    return@Thread
                }
                
                tempInterface = printer.usbDevice.getInterface(0)
                if (!tempConnection.claimInterface(tempInterface, true)) {
                    Log.e(LOG_TAG, "Failed to claim interface")
                    tempConnection.close()
                    callback(null)
                    return@Thread
                }
                
                for (i in 0 until tempInterface.endpointCount) {
                    val endpoint = tempInterface.getEndpoint(i)
                    if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK && 
                        endpoint.direction == UsbConstants.USB_DIR_OUT) {
                        tempOutEndpoint = endpoint
                        break
                    }
                }
                
                if (tempOutEndpoint == null) {
                    Log.e(LOG_TAG, "Failed to find OUT endpoint")
                    tempConnection.releaseInterface(tempInterface)
                    tempConnection.close()
                    callback(null)
                    return@Thread
                }
                
                // Update the PrinterConnection with temporary connection details
                printer.usbDeviceConnection = tempConnection
                printer.usbInterface = tempInterface
                printer.endPoint = tempOutEndpoint
                
                val serialCmd = byteArrayOf(29, 73, 68)
                Log.i(LOG_TAG, "Sending ESC/POS serial command to discovered printer via direct transfer...")
                
                val result = tempConnection.bulkTransfer(tempOutEndpoint, serialCmd, serialCmd.size, 3000)
                if (result < 0) {
                    Log.e(LOG_TAG, "Failed to send ESC/POS serial command to discovered printer via direct transfer")
                    callback(null)
                    return@Thread
                }
                
                Log.i(LOG_TAG, "ESC/POS serial command sent to discovered printer successfully")
                
                Thread.sleep(500)
                val response = readSyncLikeSDK(printer, 3000)
                handleESCPOSSerialResponse(response, printer, callback)
                
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error getting ESC/POS serial from discovered printer", e)
                callback(null)
            } finally {
                if (tempConnection != null && tempInterface != null) {
                    try {
                        tempConnection.releaseInterface(tempInterface)
                        tempConnection.close()
                        printer.usbDeviceConnection = null
                        printer.usbInterface = null
                        printer.endPoint = null
                    } catch (e: Exception) {
                        Log.w(LOG_TAG, "Error cleaning up temporary connection", e)
                    }
                }
            }
        }.start()
    }

    private fun handleESCPOSSerialResponse(response: ByteArray?, printer: PrinterConnection, callback: (String?) -> Unit) {
        Log.i(LOG_TAG, "ESC/POS response received: ${response?.size ?: 0} bytes")
        if (response != null && response.isNotEmpty()) {
            val serialNumber = String(response).trim()
            val rawHex = response.joinToString(" ") { "%02X".format(it) }
            
            val cleanedText = String(response, Charsets.UTF_8)
                .replace(Regex("[\\x00-\\x1F\\x7F-\\xFF]"), "")
                .replace(Regex("[\\r\\n\\t]"), "")
                .trim()
            
            Log.i(LOG_TAG, "ESC/POS Serial number found: '$cleanedText'")
            Log.i(LOG_TAG, "Raw hex: $rawHex")
            
            if (cleanedText.isNotEmpty()) {
                val oldKey = getPrinterKey(printer.usbDevice.vendorId, printer.usbDevice.productId)
                val newKey = getPrinterKey(printer.usbDevice.vendorId, printer.usbDevice.productId, cleanedText)
                
                if (newKey != oldKey) {
                    Log.i(LOG_TAG, "Updating key from '$oldKey' to '$newKey'")
                    
                    printerConnections[oldKey]?.let { connection ->
                        printerConnections.remove(oldKey)
                        printerConnections[newKey] = connection
                    }
                    
                    discoveredPrinters[oldKey]?.let { connection ->
                        discoveredPrinters.remove(oldKey)
                        discoveredPrinters[newKey] = connection
                    }
                }
            }
            
            callback(cleanedText)
        } else {
            Log.e(LOG_TAG, "Empty or null ESC/POS response")
            callback(null)
        }
    }

    private fun readSyncLikeSDK(printer: PrinterConnection, timeoutMs: Int): ByteArray? {
        Log.i(LOG_TAG, "Starting readSyncLikeSDK with ${timeoutMs}ms timeout")
        
        val startTime = System.currentTimeMillis()
        val inEndpoint = findInEndpoint(printer.usbInterface!!)
        
        if (inEndpoint == null) {
            Log.e(LOG_TAG, "No IN endpoint found!")
            return null
        }
        
        Log.i(LOG_TAG, "IN endpoint found, starting polling...")
        var attemptCount = 0
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            attemptCount++
            try {
                val buffer = ByteArray(1024)
                val readStatus = printer.usbDeviceConnection!!.bulkTransfer(
                    inEndpoint, buffer, buffer.size, 50
                )
                
                Log.v(LOG_TAG, "Read attempt $attemptCount: status=$readStatus")
                
                if (readStatus > 0) {
                    Log.i(LOG_TAG, "Data found on attempt $attemptCount: $readStatus bytes")
                    return buffer.copyOf(readStatus)
                }
                
                Thread.sleep(200)
                
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Read attempt $attemptCount failed: ${e.message}")
            }
        }
        
        Log.w(LOG_TAG, "Timeout reached after $attemptCount attempts")
        return ByteArray(0)
    }

    private fun findInEndpoint(usbInterface: UsbInterface): UsbEndpoint? {
        for (i in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(i)
            if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK && 
                endpoint.direction == UsbConstants.USB_DIR_IN) {
                return endpoint
            }
        }
        return null
    }

    fun getDiscoveredPrinters(): Map<String, PrinterConnection> {
        return discoveredPrinters.toMap()
    }

    fun clearDiscoveredPrinters() {
        discoveredPrinters.clear()
        Log.i(LOG_TAG, "Cleared discovered printers cache")
    }

    fun testAllPrinterInfo(vendorId: Int, productId: Int, serialNumber: String?, callback: (Map<String, String>) -> Unit) {
        Log.i(LOG_TAG, "testAllPrinterInfo called for $vendorId:$productId:$serialNumber")
        
        val key = if (serialNumber != null && serialNumber.isNotEmpty()) {
            getPrinterKey(vendorId, productId, serialNumber)
        } else {
            findPrinterKey(vendorId, productId)
        }
        
        if (key == null) {
            Log.e(LOG_TAG, "Printer not found: $vendorId:$productId:$serialNumber")
            callback(emptyMap())
            return
        }
        
        val printer = printerConnections[key]
        if (printer == null) {
            Log.e(LOG_TAG, "Printer connection not found for key: $key")
            callback(emptyMap())
            return
        }
        
        val results = mutableMapOf<String, String>()
        
        try {
            results["deviceName"] = printer.usbDevice.deviceName
            results["vendorId"] = printer.usbDevice.vendorId.toString()
            results["productId"] = printer.usbDevice.productId.toString()
            results["deviceId"] = printer.usbDevice.deviceId.toString()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                results["usbSerial"] = printer.usbDevice.serialNumber ?: "null"
                results["manufacturer"] = printer.usbDevice.manufacturerName ?: "null"
                results["productName"] = printer.usbDevice.productName ?: "null"
            } else {
                results["usbSerial"] = "Not available (Android < 5.0)"
            }
            
            Log.i(LOG_TAG, "USB Device Info:")
            results.forEach { (key, value) -> Log.i(LOG_TAG, "  $key: $value") }
            
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error getting USB info: ${e.message}")
        }
        
        val commands = mapOf(
            byteArrayOf(29, 73, 68) to "Serial Number (GS I 68)"
        )
        
        Thread {
            for ((cmd, description) in commands) {
                try {
                    Log.i(LOG_TAG, "Testing: $description")
                    
                    if (printer.usbDeviceConnection != null && printer.endPoint != null) {
                        val result = printer.usbDeviceConnection!!.bulkTransfer(
                            printer.endPoint, cmd, cmd.size, 3000
                        )
                        
                        if (result >= 0) {
                            Thread.sleep(500)
                            
                            val response = readSyncLikeSDK(printer, 2000)
                            if (response != null && response.isNotEmpty()) {
                                val rawHex = response.joinToString(" ") { "%02X".format(it) }
                                val cleanedText = String(response)
                                    .replace(Regex("[\\x00-\\x1F\\x7F-\\xFF]"), "")
                                    .trim()
                                
                                Log.i(LOG_TAG, "$description -> Raw: [$rawHex]")
                                Log.i(LOG_TAG, "$description -> Cleaned: '$cleanedText'")
                                
                                results[description] = cleanedText
                                results["${description}_raw"] = rawHex
                            } else {
                                Log.w(LOG_TAG, "$description -> No response")
                                results[description] = "No response"
                            }
                        } else {
                            Log.w(LOG_TAG, "$description -> Send failed")
                            results[description] = "Send failed"
                        }
                    } else {
                        Log.w(LOG_TAG, "$description -> Printer not connected")
                        results[description] = "Printer not connected"
                    }
                    
                    Thread.sleep(300)
                    
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Error testing $description: ${e.message}")
                    results[description] = "Error: ${e.message}"
                }
            }
            
            callback(results)
        }.start()
    }

    fun getPrinterStatus(vendorId: Int, productId: Int, serialNumber: String?, callback: (String) -> Unit) {
        Log.i(LOG_TAG, "getPrinterStatus called for $vendorId:$productId:$serialNumber")
        
        val key = if (serialNumber != null && serialNumber.isNotEmpty()) {
            getPrinterKey(vendorId, productId, serialNumber)
        } else {
            findPrinterKey(vendorId, productId)
        }
        
        if (key == null) {
            Log.e(LOG_TAG, "Printer not found: $vendorId:$productId:$serialNumber")
            callback("NO_RESPONSE")
            return
        }
        
        val printer = printerConnections[key]
        if (printer == null) {
            Log.e(LOG_TAG, "Printer connection not found for key: $key")
            callback("NO_RESPONSE")
            return
        }
        
        val statusCmd = byteArrayOf(0x10.toByte(), 0x04.toByte(), 0x02.toByte())
        Log.i(LOG_TAG, "Sending status command: ${statusCmd.contentToString()}")
        
        Thread {
            try {
                if (printer.usbDeviceConnection != null && printer.endPoint != null) {
                    val result = printer.usbDeviceConnection!!.bulkTransfer(
                        printer.endPoint, statusCmd, statusCmd.size, 3000
                    )
                    
                    if (result >= 0) {
                        Log.i(LOG_TAG, "Status command sent successfully")
                        Thread.sleep(300)
                        
                        val response = readSyncLikeSDK(printer, 5000)
                        
                        if (response != null && response.isNotEmpty()) {
                            Log.i(LOG_TAG, "Status response received: ${response.size} bytes")
                            Log.i(LOG_TAG, "Raw status bytes: ${response.joinToString { "%02X".format(it) }}")
                            
                            val statusCode = processStatusResponseToString(response[0])
                            Log.i(LOG_TAG, "Processed status code: $statusCode")
                            callback(statusCode)
                        } else {
                            Log.e(LOG_TAG, "No status response received")
                            callback("NO_RESPONSE")
                        }
                    } else {
                        Log.e(LOG_TAG, "Failed to send status command")
                        callback("NO_RESPONSE")
                    }
                } else {
                    Log.e(LOG_TAG, "Printer not connected")
                    callback("NO_RESPONSE")
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error getting printer status", e)
                callback("NO_RESPONSE")
            }
        }.start()
    }

    private fun processStatusResponseToString(data: Byte): String {
        Log.i(LOG_TAG, "Processing status byte: 0x${"%02X".format(data)} (${data.toInt()})")
        
        val statusByte = data.toInt() and 0xFF
        
        return when {
            data < 0 -> {
                Log.i(LOG_TAG, "Status: Negative value - $data")
                "ERROR_NEGATIVE_STATUS"
            }
            (statusByte and 0x08) > 0 -> {
                Log.i(LOG_TAG, "Status: Paper end (bit 3 set)")
                "PAPER_END"
            }
            (statusByte and 0x04) > 0 -> {
                Log.i(LOG_TAG, "Status: Paper near end (bit 2 set)")
                "COVER_OPEN"
            }
            (statusByte and 0x20) > 0 -> {
                Log.i(LOG_TAG, "Status: Cover open (bit 5 set)")
                "OUT OF PAPER"
            }
            (statusByte and 0x40) > 0 -> {
                Log.i(LOG_TAG, "Status: Error (bit 6 set)")
                "PRINTER_ERROR"
            }
            (statusByte and 0x10) > 0 && (statusByte and 0x02) > 0 -> {
                Log.i(LOG_TAG, "Status: Special condition (bits 4 and 1 set)")
                "ONLINE_READY"
            }
            statusByte == 0x12 -> {
                Log.i(LOG_TAG, "Status: Online and ready")
                "ONLINE_READY"
            }
            statusByte == 0x16 -> {
                Log.i(LOG_TAG, "Status: Online but cover open")
                "ONLINE_COVER_OPEN"
            }
            statusByte == 0x32 -> {
                Log.i(LOG_TAG, "Status: Online but paper end")
                "ONLINE_PAPER_END"
            }
            else -> {
                Log.i(LOG_TAG, "Status: Unknown status - 0x${"%02X".format(statusByte)}")
                "UNKNOWN_STATUS_0x${"%02X".format(statusByte)}"
            }
        }
    }

}