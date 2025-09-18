package app.mylekha.client.flutter_usb_printer

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.annotation.NonNull
import app.mylekha.client.flutter_usb_printer.adapter.USBPrinterAdapter
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import android.util.Log



/** FlutterUsbPrinterPlugin */
class FlutterUsbPrinterPlugin: FlutterPlugin, MethodCallHandler, ActivityAware {
  private var adapter: USBPrinterAdapter? = null
  /// The MethodChannel that will the communication between Flutter and native Android
  ///
  /// This local reference serves to register the plugin with the Flutter Engine and unregister it
  /// when the Flutter Engine is detached from the Activity
  private lateinit var channel : MethodChannel
  private lateinit var activity: Activity
  private lateinit var context: Context

  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, "flutter_usb_printer")
    channel.setMethodCallHandler(this)
    context = flutterPluginBinding.getApplicationContext()
    adapter = USBPrinterAdapter().getInstance()
  }

  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
    when (call.method) {
        "getUSBDeviceList" -> {
          getUSBDeviceList(result)
        }
        "connect" -> {
          val vendorId = call.argument<Int>("vendorId")
          val productId = call.argument<Int>("productId")
          val serialNumber = call.argument<String>("serialNumber") // Optional parameter
          connect(vendorId!!, productId!!, serialNumber, result)
        }
        "close" -> {
          close(result)
        }
        "printText" -> {
          val vendorId = call.argument<Int>("vendorId")
          val productId = call.argument<Int>("productId")
          val serialNumber = call.argument<String>("serialNumber") // Optional parameter
          val text = call.argument<String>("text")
          printText(vendorId!!, productId!!, serialNumber, text, result)
        }
        "printRawText" -> {
          val vendorId = call.argument<Int>("vendorId")
          val productId = call.argument<Int>("productId")
          val serialNumber = call.argument<String>("serialNumber") // Optional parameter
          val raw = call.argument<String>("raw")
          printRawText(vendorId!!, productId!!, serialNumber, raw, result)
        }
        "write" -> {
          val vendorId = call.argument<Int>("vendorId")
          val productId = call.argument<Int>("productId")
          val serialNumber = call.argument<String>("serialNumber") // Optional parameter
          val data = call.argument<ByteArray>("data")
          write(vendorId!!, productId!!, serialNumber, data, result)
        }
        "getPrinterSerial" -> {
            val vendorId = call.argument<Int>("vendorId")
            val productId = call.argument<Int>("productId")
             val serialNumber = call.argument<String>("serialNumber") // Op
          
            getPrinterSerial(vendorId!!, productId!!, serialNumber, result)
        }
        "testAllPrinterInfo" -> {
        val vendorId = call.argument<Int>("vendorId")
        val productId = call.argument<Int>("productId")
       // testAllPrinterInfo(vendorId!!, productId!!, result)
        }
        "getPrinterStatus" -> {
        val vendorId = call.argument<Int>("vendorId")
        val productId = call.argument<Int>("productId")
      //  getPrinterStatus(vendorId!!, productId!!, result)
        }

        
        else -> {
          result.notImplemented()
        }
    }
  }

  private fun getUSBDeviceList(result: Result) {

    val usbDevices = adapter!!.getDeviceList()
    val list = ArrayList<HashMap<String, String?>>()
    for (usbDevice in usbDevices) {
      val deviceMap: HashMap<String, String?> = HashMap()

        Log.d("USB_DEBUG", "USB Device: $usbDevice")
      deviceMap["deviceName"] = usbDevice.deviceName
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        deviceMap["manufacturer"] = usbDevice.manufacturerName
      }else{
        deviceMap["manufacturer"] = "unknown";
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        deviceMap["productName"] = usbDevice.productName
      }else{
        deviceMap["productName"] = "unknown";
      }

       if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            deviceMap["serial_number"] = usbDevice.serialNumber ?: "unknown"
            Log.d("USB_DEBUG", "Serial Number: ${usbDevice.serialNumber}")
        } else {
            deviceMap["serialNumber"] = "not_available"
        }
      deviceMap["deviceId"] = Integer.toString(usbDevice.deviceId)
      deviceMap["vendorId"] = Integer.toString(usbDevice.vendorId)
      deviceMap["productId"] = Integer.toString(usbDevice.productId)
      list.add(deviceMap)
      print("usbDevice ${usbDevice}");
    }
    result.success(list)
  }

  private fun connect(vendorId: Int, productId: Int, serialNumber: String?, result: Result) {
    if (!adapter!!.selectDevice(vendorId, productId, serialNumber)) {
      result.success(false)
    } else {
      result.success(true)
    }
  }

  private fun close(result: Result) {
    adapter!!.closeConnectionIfExists()
    result.success(true)
  }

  private fun printText(vendorId: Int, productId: Int, serialNumber: String?, text: String?, result: Result) {
      text?.let {
          val success = adapter!!.printText(vendorId, productId, serialNumber, it)
          result.success(success)
      } ?: result.error("ERROR", "Text cannot be null", null)
  }

  private fun printRawText(vendorId: Int, productId: Int, serialNumber: String?, base64Data: String?, result: Result) {
      base64Data?.let {
          val success = adapter!!.printRawText(vendorId, productId, serialNumber, it)
          result.success(success)
      } ?: result.error("ERROR", "Base64 data cannot be null", null)
  }

 private fun write(vendorId: Int, productId: Int, serialNumber: String?, bytes: ByteArray?, result: Result) {
    Log.i("DEBUG", "Plugin write called with: vendorId=$vendorId, productId=$productId, serial=$serialNumber")
    bytes?.let {
        val success = adapter!!.write(vendorId, productId, serialNumber, it)
        result.success(success)
    } ?: result.error("ERROR", "Bytes cannot be null", null)
}

   private fun getPrinterSerial(vendorId: Int, productId: Int, serial: String?,  result: Result) {
    adapter!!.getPrinterSerial(vendorId, productId ,serial) { serialNumber ->
        // This callback runs on a background thread, so we need to run the result on main thread
        activity.runOnUiThread {
            if (serialNumber != null) {
                result.success(serialNumber)
            } else {
                result.error("SERIAL_ERROR", "Could not retrieve printer serial number", null)
            }
        }
    }
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    print("onAttachedToActivity")
    activity = binding.activity
    adapter!!.init(activity);
  }

  override fun onDetachedFromActivityForConfigChanges() {
    // This call will be followed by onReattachedToActivityForConfigChanges().
    print("onDetachedFromActivityForConfigChanges");
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    print("onAttachedToActivity")
    onAttachedToActivity(binding)
  }

  override fun onDetachedFromActivity() {
    // This call will be followed by onDetachedFromActivity().
    print("onDetachedFromActivity")
  }

  // private fun testAllPrinterInfo(vendorId: Int, productId: Int, result: Result) {
  //   adapter!!.testAllPrinterInfo(vendorId, productId) { info ->
  //       activity.runOnUiThread {
  //           result.success(info)
  //       }
  //   }
  // }
  
 
}