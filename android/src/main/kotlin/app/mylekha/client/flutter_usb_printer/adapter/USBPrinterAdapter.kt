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




    fun getInstance(): USBPrinterAdapter? {
        if (mInstance == null) {
            mInstance = this;
        }
        return mInstance
    }

    private fun getPrinterKey(vendorId: Int, productId: Int): String {
        return "$vendorId:$productId"
    }

    private val mUsbDeviceReceiver: BroadcastReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val usbDevice = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
        val key = getPrinterKey(usbDevice.vendorId, usbDevice.productId)

        if (ACTION_USB_PERMISSION == action) {
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                Log.i(LOG_TAG, "Permission granted for device: $key")
                printerConnections[key] = PrinterConnection(usbDevice, null, null, null)
                openConnection(usbDevice.vendorId, usbDevice.productId)
            } else {
                Toast.makeText(context, "Permission denied for $key", Toast.LENGTH_LONG).show()
            }
        } else if (UsbManager.ACTION_USB_DEVICE_DETACHED == action) {
            if (printerConnections.containsKey(key)) {
                Toast.makeText(context, "USB device disconnected: $key", Toast.LENGTH_LONG).show()
                closeConnection(key)
                printerConnections.remove(key)
            }
        }
    }
}

    fun init(reactContext: Context?) {
        mContext = reactContext
        mUSBManager = mContext!!.getSystemService(Context.USB_SERVICE) as UsbManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            mPermissionIntent =
                PendingIntent.getBroadcast(mContext, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        } else {
            mPermissionIntent =
                PendingIntent.getBroadcast(mContext, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
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
            mUsbDevice=null
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
        return ArrayList(mUSBManager!!.deviceList.values)
    }

    fun selectDevice(vendorId: Int, productId: Int): Boolean {
        val key = getPrinterKey(vendorId, productId)
    
        // Check if the printer is already connected
        if (printerConnections.containsKey(key)) {
            Log.v(LOG_TAG, "Printer already connected: $key")
            return true
        }

        // Find the desired USB device
        val usbDevices = getDeviceList()
        val selectedDevice = usbDevices.find { it.vendorId == vendorId && it.productId == productId }
        
        if (selectedDevice != null) {
            Log.v(LOG_TAG, "Requesting permission for device: $key")
            mUSBManager!!.requestPermission(selectedDevice, mPermissionIntent)
            return true
        }
        return false
    }

    fun openConnection(vendorId: Int, productId: Int): Boolean {
        val key = getPrinterKey(vendorId, productId)
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


    fun printText(vendorId: Int, productId: Int, text: String): Boolean {
        val key = getPrinterKey(vendorId, productId)
        val printer = printerConnections[key] ?: return false

        return if (openConnection(vendorId, productId)) {
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


    fun printRawText(vendorId: Int, productId: Int, data: String): Boolean {
        val key = getPrinterKey(vendorId, productId)
        val printer = printerConnections[key] ?: return false  // Retrieve the printer connection

        return if (openConnection(vendorId, productId)) {
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

    fun write(vendorId: Int, productId: Int, bytes: ByteArray): Boolean {
        val key = getPrinterKey(vendorId, productId)
        val printer = printerConnections[key] ?: return false  // Retrieve the printer connection

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
            return true  // The thread has been started, assume success
        } else {
            //Log.e(LOG_TAG, "Printer not connected: $key")
            return false
        }
    }

    fun getPrinterSerial(vendorId: Int, productId: Int, callback: (String?) -> Unit) {
    Log.i(LOG_TAG, "getPrinterSerial called for $vendorId:$productId")
    
    val key = getPrinterKey(vendorId, productId)
    val printer = printerConnections[key]
    
    if (printer == null) {
        Log.e(LOG_TAG, "Printer not found in connections: $key")
        callback(null)
        return
    }
    
    // Use your existing write method to send the command
    val serialCmd = byteArrayOf(29, 73, 68)
    Log.i(LOG_TAG, "Sending serial command via write method...")
    
    val success = write(vendorId, productId, serialCmd)
    
    if (success) {
        Log.i(LOG_TAG, "Serial command sent successfully via write method")
        
        // Now try to read the response
        Thread {
            Thread.sleep(500) // Give printer time to process
            
            val response = readSyncLikeSDK(printer, 3000)
            Log.i(LOG_TAG, "Response received: ${response?.size ?: 0} bytes")
            
            if (response != null && response.isNotEmpty()) {
                val serialNumber = String(response).trim()
                Log.i(LOG_TAG, "Serial number found: '$serialNumber'")
                callback(serialNumber)
            } else {
                Log.e(LOG_TAG, "Empty or null response")
                callback(null)
            }
        }.start()
        
    } else {
        Log.e(LOG_TAG, "Failed to send serial command via write method")
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



// Helper method to find the IN endpoint for reading responses
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


fun testAllPrinterInfo(vendorId: Int, productId: Int, callback: (Map<String, String>) -> Unit) {
    Log.i(LOG_TAG, "testAllPrinterInfo called for $vendorId:$productId")
    
    val key = getPrinterKey(vendorId, productId)
    val printer = printerConnections[key]
    
    if (printer == null) {
        callback(emptyMap())
        return
    }
    
    val results = mutableMapOf<String, String>()
    
    // First get USB device info
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
    
    // Now test different ESC/POS commands
    val commands = mapOf(
        byteArrayOf(29, 73, 65) to "Firmware Version (GS I 65)",
        byteArrayOf(29, 73, 66) to "Maker Name (GS I 66)",
        byteArrayOf(29, 73, 67) to "Model Name (GS I 67)",
        byteArrayOf(29, 73, 68) to "Serial Number (GS I 68)",
        byteArrayOf(29, 73, 1) to "Printer Model ID (GS I 1)",
        byteArrayOf(29, 73, 2) to "Type ID (GS I 2)",
        byteArrayOf(29, 73, 3) to "Version ID (GS I 3)"
    )
    
    Thread {
        for ((cmd, description) in commands) {
            try {
                Log.i(LOG_TAG, "Testing: $description")
                
                val success = write(vendorId, productId, cmd)
                if (success) {
                    Thread.sleep(500) // Wait for response
                    
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
                    results[description] = "Send failed";
                }
                
                Thread.sleep(300) // Wait between commands
                
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error testing $description: ${e.message}")
                results[description] = "Error: ${e.message}"
            }
        }
        
        // Return all results
        callback(results)
    }.start()
}

fun getPrinterStatus(vendorId: Int, productId: Int, callback: (String) -> Unit) {
    Log.i(LOG_TAG, "getPrinterStatus called for $vendorId:$productId")
    
    val key = getPrinterKey(vendorId, productId)
    val printer = printerConnections[key]
    
    if (printer == null) {
        Log.e(LOG_TAG, "Printer not found in connections: $key")
        callback("NO_RESPONSE")
        return
    }
    
    // The SDK calls printerCheck(2, 5000, callback)
    // printerCheck(2, ...) likely sends a status check command
    // Common ESC/POS status commands: DLE EOT n or GS a
    val statusCmd = byteArrayOf(0x10.toByte(), 0x04.toByte(), 0x02.toByte()) // DLE EOT 2 (printer status)
    
    Log.i(LOG_TAG, "Sending status command: ${statusCmd.contentToString()}")
    
    val success = write(vendorId, productId, statusCmd)
    
    if (success) {
        Log.i(LOG_TAG, "Status command sent successfully")
        
        Thread {
            Thread.sleep(300) // Give printer time to respond
            
            val response = readSyncLikeSDK(printer, 5000) // Same 5000ms timeout as SDK
            
            if (response != null && response.isNotEmpty()) {
                Log.i(LOG_TAG, "Status response received: ${response.size} bytes")
                Log.i(LOG_TAG, "Raw status bytes: ${response.joinToString { "%02X".format(it) }}")
                
                // Process the response like the SDK does
                val statusCode = processStatusResponseToString(response[0])
                Log.i(LOG_TAG, "Processed status code: $statusCode")
                callback(statusCode)
            } else {
                Log.e(LOG_TAG, "No status response received")
                callback("NO_RESPONSE")
            }
        }.start()
        
    } else {
        Log.e(LOG_TAG, "Failed to send status command")
        callback("NO_RESPONSE")
    }
}

// Process status response exactly like the SDK does
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
