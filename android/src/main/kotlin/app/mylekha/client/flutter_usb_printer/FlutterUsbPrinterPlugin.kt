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
          connect(vendorId!!, productId!!, result)
        }
        "close" -> {
          close(result)
        }
        "printText" -> {
          val vendorId = call.argument<Int>("vendorId")
          val productId = call.argument<Int>("productId")
          val text = call.argument<String>("text")
          printText(vendorId!!, productId!!, text, result)
        }
        "printRawText" -> {
          val vendorId = call.argument<Int>("vendorId")
          val productId = call.argument<Int>("productId")
          val raw = call.argument<String>("raw")
          printRawText(vendorId!!, productId!!, raw, result)
        }
        "write" -> {
          val vendorId = call.argument<Int>("vendorId")
          val productId = call.argument<Int>("productId")
          val data = call.argument<ByteArray>("data")
          write(vendorId!!, productId!!, data, result)
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
      deviceMap["deviceId"] = Integer.toString(usbDevice.deviceId)
      deviceMap["vendorId"] = Integer.toString(usbDevice.vendorId)
      deviceMap["productId"] = Integer.toString(usbDevice.productId)
      list.add(deviceMap)
      print("usbDevice ${usbDevice}");
    }
    result.success(list)
  }

  private fun connect(vendorId: Int, productId: Int, result: Result) {
    if (!adapter!!.selectDevice(vendorId!!, productId!!)) {
      result.success(false)
    } else {
      result.success(true)
    }
  }

  private fun close(result: Result) {
    adapter!!.closeConnectionIfExists()
    result.success(true)
  }

 private fun printText(vendorId: Int, productId: Int, text: String?, result: Result) {
      text?.let {
          val success = adapter!!.printText(vendorId, productId, it)
          result.success(success)
      } ?: result.error("ERROR", "Text cannot be null", null)
  }

  private fun printRawText(vendorId: Int, productId: Int, base64Data: String?, result: Result) {
      base64Data?.let {
          val success = adapter!!.printRawText(vendorId, productId, it)
          result.success(success)
      } ?: result.error("ERROR", "Base64 data cannot be null", null)
  }

  private fun write(vendorId: Int, productId: Int, bytes: ByteArray?, result: Result) {
      bytes?.let {
          val success = adapter!!.write(vendorId, productId, it)
          result.success(success)
      } ?: result.error("ERROR", "Bytes cannot be null", null)
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
}
