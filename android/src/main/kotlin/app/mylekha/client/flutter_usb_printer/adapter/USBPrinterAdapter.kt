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
            Log.e(LOG_TAG, "Printer not connected: $key")
            return false
        }
    }


}
