package com.ddl.ivygateap.xengineSDK.localDNS

import android.util.Log
import com.ddl.ivygateap.IvyFileUtil
import com.ddl.ivygateap.xengineSDK.XNetService

internal object WhiteList {
    private val TAG = WhiteList::class.java.simpleName

//    private val HostSet = setOf<String>(
////        "(^|.*.)amazonaws.com",
////        "(^|.*.)colorado.edu",
////        "(^|.*.)doubleclick.net",
////        "(^|.*.)google-analytics.com",
////        "(^|.*.)googleapis.com",
////        "(^|.*.)googlesyndication.com",
////        "(^|.*.)googletagmanager.com",
////        "(^|.*.)kastatic.org",
////        "(^|.*.)khanacademy.org",
////        "(^|.*.)kidsa-z.com",
////        "(^|.*.)litix.io",
////        "(^|.*.)qualaroo.com",
////        "(^|.*.)scorecardresearch.com",
////        "(^|.*.)ssl.google-analytics.com",
//        "(^|.*.)ted.com",
//        "(^|.*.)tedcdn.com",
////        "(^|.*.)akamaihd.net",
//
//        "(^|.*.)ustc.edu.cn$",
//        "(^|.*.)ip.sb$")

    private val HostSet by lazy {
        val whiteListContent: String = IvyFileUtil.readString(IvyFileUtil.createFileIfNotExist(IvyFileUtil.getCacheDir(XNetService.app.applicationContext), "DDL_WHITE_LIST.txt")) ?: ""
        val list = whiteListContent.split(",")
        val set = mutableSetOf<String>()
        list.forEach { item -> "(^|.*.)$item".also { set.add(it) } }
        set.toSet()
    }

    private val HostRegexSet: MutableSet<Regex> = HashSet()

    init {
        for (h in HostSet) {
            HostRegexSet.add(h.toRegex())
        }
    }

    fun isInWhiteList(host: String): Boolean {
        // short test block
        //return host != "test.light.ustclug.org"

        var bMatch = false

        for (hr in HostRegexSet) {
            if (host.matches(hr)) {
                Log.d(TAG, "$host matches $hr")
                bMatch = true
                break
            }
        }

        return bMatch
    }
}
