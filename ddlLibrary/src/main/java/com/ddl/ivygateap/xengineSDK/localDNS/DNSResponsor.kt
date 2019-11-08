package com.ddl.ivygateap.xengineSDK.localDNS

import java.net.DatagramPacket
import android.util.Log

internal class DNSResponsor constructor(PacketIn: DatagramPacket) {
    companion object {
        private val TAG = DNSResponsor::class.java.simpleName
        val nMaxPacketLength = 512 // max length for DNS UDP packet
        private val nAddrSec = 4
        private val nAnswerLength = 16
    }

    private var PayloadIn = ByteArray(nMaxPacketLength)
    var PayloadResponse = ByteArray(nMaxPacketLength)
        private set
    var nResponseLength = 0
        private set
    private var nHostByteCount = 0
    var strHost = ""
        private set
    private var bNullResp = true
    private var curHeadCount = 12

    private var IPArray = ByteArray(nAddrSec)

    init {
        // get the payload and prepare for response
        PayloadIn = PacketIn.data.copyOf()

        Log.d(TAG,
            "Received data from: "
                    + PacketIn.address.toString().substring(1) + ":" + PacketIn.port
                    + " with length: " + PacketIn.length
        )

        parse() // parse the request just after init
    }

    private fun decodeDoubleBytes(bytes: ByteArray, pos: Int): Int
    {
        return bytes[pos].toInt() * 256 + bytes[pos + 1]
    }

    private fun encodeDoubleBytes(value: Int, bytes: ByteArray, pos: Int)
    {
        bytes[pos + 1] = value.rem(256).toByte();
        val value_high = value.div(256)
        bytes[pos] = value_high.toByte()
    }

    private fun parseHost() {
        val host = CharArray(nHostByteCount - 1)
        var a = 0
        var lc = PayloadIn[curHeadCount].toInt() // lc is the nLength of the first URL's "token" (e.g. "www" has lc=3)
        var count = 0
        var flag = true
        while (flag) {
            while (a < lc) { // extract the token from payload
                host[a + count] = PayloadIn[curHeadCount + a + count + 1].toChar()
                a++
            }
            if (a + count == nHostByteCount - 1) { // check if the URL's string has ended
                flag = false
            } else {
                host[a + count] =
                    '.' // separate the extracted token from the next so to create a well formed http address
            }
            a++
            count = a + count
            a = 0
            lc = PayloadIn[curHeadCount + count].toInt() // lc is the nLength of the next token
        }

        // store the host string
        strHost = String(host)
        Log.d(TAG, "Asking for: $strHost")
    }

    fun parse() {
        // check the number of questions
        var pos = 4
        val nQuestions = decodeDoubleBytes(PayloadIn, pos);
        // TODO: deal with more than 1 question

        // search the end of the requested URL String
        // nURLByteCount - 1 is the length (in byte) of the requested URL
        // e.g. "www.baidu.com" has c = 14, which means "3www5baidu3com"
        // while nURLByteCount + 1 is the length of the URL section in bytes (including a \0 for ending)
        var stop = false
        while (!stop) {
            if (PayloadIn[12 + nHostByteCount] == 0.toByte()) {
                stop = true
            } else {
                nHostByteCount++
            }
        }

        // check the query type & class
        // we only response the A record & IN class query
        pos = curHeadCount + nHostByteCount + 1
        val type = decodeDoubleBytes(PayloadIn, pos)
        val cls = decodeDoubleBytes(PayloadIn, pos + 2)
        if (type == 1 && cls == 1) {
            bNullResp = false; // set the flag for null response
            parseHost()
            Log.d(TAG, "A record")
        } else if (type == 28 && cls == 1) {
            parseHost()
            Log.d(TAG, "AAAA record")
        } else {
            Log.e(TAG, "unknown type!")
        }
    }

    @kotlin.ExperimentalUnsignedTypes
    fun genResponse() {
        // pool a new IP addr for response
        IPArray = IPPool.pool(strHost)
        Log.d(TAG, "$strHost: ${IPArray[0].toUByte()}.${IPArray[1].toUByte()}.${IPArray[2].toUByte()}.${IPArray[3].toUByte()}")

        val flag = curHeadCount + (nHostByteCount + 1) + 4 // head + host + query type
        // only copy needed parts into answer
        // just a truncation of additional records
        PayloadResponse = PayloadIn.copyOf(flag)
        nResponseLength = flag

        // Change Flag in 0x8180 (set the first bit to "1" which means "response")
        encodeDoubleBytes(0x8180, PayloadResponse, 2)

        // set authority and additional records to 0
        encodeDoubleBytes(0x0000, PayloadResponse, 8)
        encodeDoubleBytes(0x0000, PayloadResponse, 10)

        if (!bNullResp) {
            // resize for answer section (16 bytes)
            PayloadResponse = PayloadResponse.copyOf(flag + nAnswerLength)
            // renew the response length
            nResponseLength = flag + nAnswerLength

            // Change Answer_RRs in 0x0001
            encodeDoubleBytes(0x0001, PayloadResponse, 6)

            //write the end of a generic DNS response
            // 0xc00c
            encodeDoubleBytes(0xc00c, PayloadResponse, flag)
            // 0x0001 Type A
            encodeDoubleBytes(0x0001, PayloadResponse, flag + 2)
            // 0x0001 class IN
            encodeDoubleBytes(0x0001, PayloadResponse, flag + 4)
            // TTL 32
            encodeDoubleBytes(0, PayloadResponse, flag + 6)
            encodeDoubleBytes(32, PayloadResponse, flag + 8)
            // 4 bytes for an ipv4 addr
            encodeDoubleBytes(4, PayloadResponse, flag + 10)
            // ipv4 addr
            PayloadResponse[flag + 12] = IPArray[0]
            PayloadResponse[flag + 13] = IPArray[1]
            PayloadResponse[flag + 14] = IPArray[2]
            PayloadResponse[flag + 15] = IPArray[3]
        }
    }
}
