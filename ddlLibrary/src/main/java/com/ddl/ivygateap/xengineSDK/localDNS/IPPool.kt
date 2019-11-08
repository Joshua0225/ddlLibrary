package com.ddl.ivygateap.xengineSDK.localDNS

import android.util.Log
import kotlin.collections.ArrayList
import kotlin.collections.MutableList
import kotlin.collections.HashMap

object IPPool {
    private val TAG = IPPool::class.java.simpleName

    // one-to-one mapping of hostnames(String) and addresses(Inet4Address)
    private val HostMap = HashMap<String, Int>()
    // host list with HostAddr - BaseAddr as its index
    private val HostList: MutableList<String> = ArrayList() // FIXME: ArrayList may not be the best choice

    // base(first) ip is set to 172.16.0.1, and the whole block available is 172.16.0.0/12
    private val BaseIP = 172.shl(24) + 16.shl(16) + 1;

    private fun transInt2ByteArray(ip: Int): ByteArray {
        return byteArrayOf(
            ip.ushr(24).toByte(),
            ip.ushr(16).toByte(),
            ip.ushr(8).toByte(),
            ip.toByte()) // ushr() here may be safer
    }

    private fun transByteArray2Int(addr: ByteArray): Int {
        return addr[0].toInt().shl(24) +
                addr[1].toInt().shl(16) +
                addr[2].toInt().shl(8) +
                addr[3].toInt()
    }

    fun pool(host: String): ByteArray {
        if (HostMap.contains(host)) {
            return transInt2ByteArray(HostMap[host]!!)
        } else {
            val newIP = BaseIP + HostList.size
            HostMap[host] = newIP
            HostList.add(host)

            return transInt2ByteArray(newIP)
        }
    }

    // TODO: need test
    fun getHost(addr: ByteArray): String {
        val ip = transByteArray2Int(addr)
        val index = ip - BaseIP
        if (index >= HostList.size) {
            Log.w(TAG, "unknown host")
            return ""
        } else {
            Log.d(TAG, "known host")
            return HostList[index]
        }
    }
}
