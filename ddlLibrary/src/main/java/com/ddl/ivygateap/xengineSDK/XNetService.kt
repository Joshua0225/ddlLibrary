package com.ddl.ivygateap.xengineSDK

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.VpnService
import android.os.*
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.text.TextUtils
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ddl.ivygateap.IRemoteCallback
import com.ddl.ivygateap.IRemoteService
import com.ddl.ivygateap.R
import com.ddl.ivygateap.secondarydevelopment.DDLVpnState
import com.ddl.ivygateap.xengineSDK.localDNS.DNSServer
import kotlinx.coroutines.*
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

class XNetService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private val executorService: ExecutorService = Executors.newFixedThreadPool(2) // only for DNSServer and SocketServer
    private var dns: DNSServer? = null
    private var socket: SocketServer? = null
    private val scope = CoroutineScope(Dispatchers.Default + CoroutineName("XNet"))

    private var mCallbacks: RemoteCallbackList<IRemoteCallback>? = RemoteCallbackList()

    private val stub = object : IRemoteService.Stub() {

        override fun register(callback: IRemoteCallback?) {
            if (null == callback) {
                return
            }
            Log.i(TAG, "注册回调")
            mCallbacks?.register(callback)
        }

        override fun unRegister(callback: IRemoteCallback?) {
            if (null == callback) {
                return
            }
            Log.i("LEO", "反注册回调")
            mCallbacks?.unregister(callback)
        }

        override fun getVpnState(): String {
            return when (ddlVpnState) {
                DDLVpnState.STARTING -> "starting"
                DDLVpnState.STARTED -> "started"
                DDLVpnState.STOPPING -> "stopping"
                DDLVpnState.STOPPED -> "stopped"
            }
        }

        override fun isVpnRunning(): Boolean {
            return isRunning
        }

        override fun startVpn() {
            startVPN()
        }

        override fun stopVpn() {
            stopVPN()
        }

    }


    val deviceStorage by lazy {
        DeviceStorageApp(app)
    }

    override fun onCreate() {
        super.onCreate()
    }

    private suspend fun sendFd(fd: FileDescriptor) {
        var tries = 0
        val path = File(deviceStorage.noBackupFilesDir, "sock_path").absolutePath
        while (true) try {
            delay(50L shl tries)
            LocalSocket().use { localSocket ->
                localSocket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
                localSocket.setFileDescriptorsForSend(arrayOf(fd))
                localSocket.outputStream.write(42)
            }
            Log.d(TAG, "sendFd: succeeded")
            return
        } catch (e: IOException) {
            if (tries > 5) throw e
            tries += 1
            Log.e(TAG, "sendFd: failed $tries")
        }
    }

    private fun setupSockClient() {
        if (vpnInterface == null) {
            return
        }

        //val myExec = "/data/data/com.ddl.ivygateap/libtun2socks.so"
        val cmd = File(applicationInfo.nativeLibraryDir, "libtun2socks.so").absolutePath +
                " --netif-ipaddr $SOCK_ADDRESS" +
                " --socks-server-addr 127.0.0.1:${GoProxyRunner.servicePort}" +
                " --tunmtu $VPN_MTU" +
                " --sock-path " + File(deviceStorage.noBackupFilesDir, "sock_path").absolutePath +
                " --dnsgw 127.0.0.1:${DNSServer.serverPort}" +
                " --loglevel warning" +
                " --enable-udprelay"
        Log.d(TAG, "exec " + cmd)
        Runtime.getRuntime().exec(cmd)

        scope.launch {
            try {
                sendFd(vpnInterface!!.fileDescriptor)
            } catch (e: ErrnoException) {
                Log.e(TAG, e.toString(), e)
            }
        }
    }

    private fun stopSockClient() {
        for (process in File("/proc").listFiles { _, name -> TextUtils.isDigitsOnly(name) }
                ?: return) {
            val exe = File(
                    try {
                        File(process, "cmdline").inputStream().bufferedReader().readText()
                    } catch (_: IOException) {
                        continue
                    }.split(Character.MIN_VALUE, limit = 2).first())
            if (exe.name == "libtun2socks.so") try {
                Os.kill(process.name.toInt(), OsConstants.SIGKILL)
                Log.i(TAG, "libtun2socks.so killed")
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.ESRCH) {
                    e.printStackTrace()
                    Log.w(TAG, "SIGKILL ${exe.absolutePath} (${process.name}) failed")
                }
            }
        }
    }

    private fun startVPN() {
        isRunning = true
        changeState(DDLVpnState.STARTING)

        try {
            setupVPN()
            dns = DNSServer()
            executorService.submit(dns!!)
            socket = SocketServer()
            executorService.submit(socket!!)
            GoProxyRunner.startProxy()
            setupSockClient()

            changeState(DDLVpnState.STARTED)
            Log.i(TAG, "Started")
        } catch (e: IOException) {
            // TODO: Here and elsewhere, we should explicitly notify the user of any errors
            // and suggest that they stop the service, since we can't do it ourselves
            Log.e(TAG, "Error starting service", e)
            stopVPN()
        }
    }

    private fun setupVPN() {
        if (vpnInterface == null) {
            val builder = Builder()
            builder.setSession("XEngine_VPN")
            builder.setMtu(VPN_MTU)
            builder.addAddress(VPN_ADDRESS, 32)
            builder.addRoute(VPN_ROUTE, VPN_PREFIX_LEN)
            builder.addRoute(SOCK_ADDRESS, 32)
            builder.addDnsServer(SOCK_ADDRESS)

//            if (Build.VERSION.SDK_INT >= 29) {
//                builder.setMetered(false)
//            }
            vpnInterface = builder.establish()
        }
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val channelId = "$packageName:ddl"
        var channel: NotificationChannel? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            channel = NotificationChannel(channelId, "91VPN", NotificationManager.IMPORTANCE_HIGH)
            NotificationManagerCompat.from(this).createNotificationChannel(channel)

        }
        val builder = NotificationCompat.Builder(this, channelId)
                .setWhen(0)
                .setColor(ContextCompat.getColor(this, R.color.colorNotificationBackground))
                .setTicker(this.getString(R.string.vpn_on))
                .setContentTitle("ddl")
                .setContentIntent(configureIntent(this))
                .setSmallIcon(R.drawable.ic_universal)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
        startForeground(1, builder.build())// 开始前台服务

        return android.app.Service.START_NOT_STICKY
    }

    fun stopVPN() {
        Log.i(TAG, "Cleanning up...")
        changeState(DDLVpnState.STOPPING)

        stopSockClient()
        GoProxyRunner.stopPorxy()
        dns!!.close()
        socket!!.close()

        val waitList = executorService.shutdownNow()
        if (!waitList.isEmpty()) { // WTF
            Log.wtf(TAG, "threads not executed: ${waitList.size}")
        }

        while (!executorService.awaitTermination(1, TimeUnit.SECONDS)) {
            Log.i(TAG, "waiting threads termination...")
        }
        Log.i(TAG, "thread pool terminated")

        vpnInterface!!.close()

        Log.i(TAG, "Stopped")

        isRunning = false
        changeState(DDLVpnState.STOPPED)
        stopSelf()
    }

    override fun onDestroy() {
        //销毁回调资源 否则要内存泄露
        mCallbacks?.kill()
        super.onDestroy()
    }

        private val VPN_MTU = 1500
        private val VPN_ADDRESS = "10.0.0.2" // Only IPv4 support for now
        private val SOCK_ADDRESS = "10.0.0.3" // Address for tun2socks
        private val VPN_ROUTE = "172.16.0.0" // Intercept a /12 block
        private val VPN_PREFIX_LEN = 12

        val BROADCAST_VPN_STATE = "com.ddl.ivygateap.xengineSDK.VPN_STATE"
        companion object {
            private val TAG = XNetService::class.java.simpleName

        var isRunning = false
            private set

        var ddlVpnState = DDLVpnState.STOPPED
            private set

        lateinit var app: Application
        lateinit var configureIntent: (Context) -> PendingIntent

        fun init(app: Application, configureClass: KClass<out Any>) {
            this.app = app
            this.configureIntent = {
                PendingIntent.getActivity(it, 0, Intent(it, configureClass.java)
                    .setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), 0)
            }
        }

        /**
         * https://android.googlesource.com/platform/prebuilts/runtime/+/94fec32/appcompat/hiddenapi-light-greylist.txt#9466
         */
        //private val getInt = FileDescriptor::class.java.getDeclaredMethod("getInt$")
    }


    override fun onBind(intent: Intent?): IBinder? {
        return stub
    }

    @Synchronized
    private fun changeState(state: DDLVpnState) {
        ddlVpnState = state
        val extra = when (ddlVpnState) {
            DDLVpnState.STARTING -> "starting"
            DDLVpnState.STARTED -> "started"
            DDLVpnState.STOPPING -> "stopping"
            DDLVpnState.STOPPED -> "stopped"
        }

        val len = mCallbacks?.beginBroadcast() ?: 0
        for (i in 0 until len) {
            try {
                mCallbacks?.getBroadcastItem(i)?.onStateChanged(extra)
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
        }
        // 记得要关闭广播
        mCallbacks?.finishBroadcast()

    }


}
