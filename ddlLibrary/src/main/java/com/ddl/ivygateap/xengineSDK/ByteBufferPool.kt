package com.ddl.ivygateap.xengineSDK

import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

object ByteBufferPool {
    private val TAG = ByteBufferPool::class.java.simpleName
    var BUFFER_SIZE = 8192 // TODO: Is the default size ideal?
        private set
    private val pool = ConcurrentLinkedQueue<ByteBuffer>()

    fun acquire(): ByteBuffer {
        var buffer: ByteBuffer? = pool.poll()
        if (buffer == null) {
            buffer = ByteBuffer.allocateDirect(BUFFER_SIZE) // Using DirectBuffer for zero-copy
            //Log.d(TAG, "New buffer, total " + pool.size.toString())
        }
        return buffer!! // will it be null ?
    }

    fun release(buffer: ByteBuffer) {
        buffer.clear()
        pool.offer(buffer)
        //Log.d(TAG, "Buffer released, total " + pool.size.toString())
    }

    // newSize && caused clear()
    fun setSize(size: Int) {
        if (size != BUFFER_SIZE) {
            BUFFER_SIZE = size
            clear()
        }
    }

    fun clear() = pool.clear()
}
