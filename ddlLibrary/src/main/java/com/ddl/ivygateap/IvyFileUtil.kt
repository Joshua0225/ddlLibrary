package com.ddl.ivygateap

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import java.io.*
import java.nio.channels.FileChannel
import java.nio.charset.Charset

class IvyFileUtil {

    companion object {
        //根目录
        private val DIR_BOOT = "feeker"
        //文件缓存目录
        private val DIR_CACHE = "cache"
        //数据缓存目录（Database）
        private val DIR_DATABASE = "database"
        //下载缓存目录
        private val DIR_DOWNLOAD = "download"
        //证书文件目录
        private val DIR_CERT = "cert"

        private var mFileCache: File? = null
        private var mFileDatabase: File? = null
        private var mFileDownload: File? = null
        private var mFileCert: File? = null

        //获取文件缓存目录
        fun getCacheDir(context: Context): File {
            if (mFileCache == null) {
                mFileCache = createDirectory(context, DIR_BOOT + File.separator + DIR_CACHE)
            }
            return mFileCache!!
        }

        //获取数据缓存目录（Database）
        fun getDatabaseDir(context: Context): File {
            if (mFileDatabase == null) {
                mFileDatabase = createDirectory(context, DIR_BOOT + File.separator + DIR_DATABASE)
            }
            return mFileDatabase!!
        }

        //获取下载缓存目录
        fun getDownloadDir(context: Context): File {
            if (mFileDownload == null) {
                mFileDownload = createDirectory(context, DIR_BOOT + File.separator + DIR_DOWNLOAD)
            }
            return mFileDownload!!
        }

        //获取证书目录
        fun getCertDir(context: Context): File {
            if (mFileCert == null) {
                mFileCert = createDirectory(context, DIR_BOOT + File.separator + DIR_CERT)
            }
            return mFileCert!!
        }

        //创建目录，在根（/sdcard）目录下创建
        private fun createDirectory(context: Context, directory: String): File? {
            val dirFile: File = if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
                File(context.cacheDir, directory)
            } else {
                File(Environment.getExternalStorageDirectory(), directory)
            }
            if (!dirFile.exists()) {
                val isCreate = dirFile.mkdirs()
                if(!isCreate) {
                    return null
                }
            }
            //生成nomedia文件，屏蔽扫描
            val nomedia = File(dirFile, ".nomedia")
            try {
                if (!nomedia.exists()) {
                    nomedia.createNewFile()
                    val nomediaFos = FileOutputStream(nomedia)
                    nomediaFos.flush()
                    nomediaFos.close()
                }
            } catch (e: IOException) {
                Log.e("IOException", "exception in createNewFile() method")
                return null
            }

            return dirFile
        }

        fun createFileIfNotExist(dirFile: File, fileName: String): String {
            return createIfNotExist(dirFile.absolutePath + File.separator + fileName)
        }

        /**
         * 如果文件不存在，就创建文件
         * @param path 文件路径
         * @return
         */
        fun createIfNotExist(path: String): String {
            val file = File(path)
            if (!file.exists()) {
                try {
                    val dir = file.parent
                    val dirFile = File(dir)
                    if (!dirFile.exists()) {
                        dirFile.mkdirs()
                    }
                    file.createNewFile()
                } catch (e: Exception) {
                    println(e.message)
                }

            }
            return path
        }

        /**
         * 创建文件，在指定的目录下创建文件,如查存在了，则删除后重新创建
         */
        fun createNewFile(dirFile: File, fileName: String): File {
            val newFile = File(dirFile, fileName)
            try {
                if (!newFile.exists() || newFile.delete()) {
                    newFile.createNewFile()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            return newFile
        }

        /**
         * 向文件中写入数据
         *
         * @param filePath
         * 目标文件全路径
         * @param data
         * 要写入的数据
         * @return true表示写入成功  false表示写入失败
         */
        fun writeBytes(filePath: String, data: ByteArray): Boolean {
            try {
                val fos = FileOutputStream(filePath)
                fos.write(data)
                fos.flush()
                fos.close()
                return true
            } catch (e: Exception) {
                println(e.message)
            }

            return false
        }

        /**
         * 从文件中读取数据
         *
         * @param file
         * @return
         */
        fun readBytes(file: String): ByteArray? {
            try {
                val fis = FileInputStream(file)
                val len = fis.available()
                val buffer = ByteArray(len)
                fis.read(buffer)
                fis.close()
                return buffer
            } catch (e: Exception) {
                println(e.message)
            }

            return null

        }

        /**
         * 向文件中写入字符串String类型的内容
         *
         * @param file
         * 文件路径
         * @param content
         * 文件内容
         * @param charset
         * 写入时候所使用的字符集
         */
        @JvmOverloads
        fun writeString(file: String, content: String, charset: String = "UTF-8") {
            try {
                val data = content.toByteArray(charset(charset))
                writeBytes(file, data)
            } catch (e: Exception) {
                println(e.message)
            }

        }

        /**
         * 从文件中读取数据，返回类型是字符串String类型
         *
         * @param file
         * 文件路径
         * @param charset
         * 读取文件时使用的字符集，如UTF-8、GBK等
         * @return
         */
        @JvmOverloads
        fun readString(file: String, charset: String = "UTF-8"): String? {
            val data = readBytes(file)
            var ret: String? = null

            try {
                ret = data?.let { String(it, Charset.forName(charset)) }
            } catch (e: Exception) {
                println(e.message)
            }

            return ret
        }


        /**
         * 定义文件保存的方法，写入到文件中，所以是输出流
         */
        fun writeFileWithAppend(path: String, content: String) {
            var out: BufferedWriter? = null
            try {
                out = BufferedWriter(
                        OutputStreamWriter(
                                FileOutputStream(path, true)
                        )
                )
                out.write(content)
                out.write("\r\n")//写入换行
            } catch (e: IOException) {
                e.printStackTrace()
            } finally {
                if (out != null) {
                    try {
                        out.close()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }

                }
            }
        }

        //计算文件的大小
        fun calculateFileSize(file: File): Long {
            var size = 0L
            try {
                val files = file.listFiles()
                for (newFile in files) {
                    if (newFile.isDirectory) {
                        size += calculateFileSize(newFile)
                    } else {
                        size += newFile.length()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            return size
        }

        //删除文件
        fun deleteFile(file: File) {
            try {
                if (file.isFile) {// 是文件
                    file.delete()
                    return
                }
                val files = file.listFiles()
                for (newFile in files) {
                    deleteFile(newFile)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

        }

        //删除含有指定名称的文件
        fun deleteFile(file: File, name: String?) {
            try {
                if (file.isFile) {// 是文件
                    if (name != null && name.trim { it <= ' ' }.isNotEmpty() && file.name.contains(name)) {
                        file.delete()
                    }
                    return
                }
                val files = file.listFiles()
                for (newFile in files) {
                    deleteFile(newFile)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

        }

        //扫描文件
        fun scanFile(context: Context) {
            try {
                val uri = Uri.parse("file://" + Environment.getExternalStorageDirectory())
                context.sendBroadcast(Intent(Intent.ACTION_MEDIA_MOUNTED, uri))
            } catch (e: Exception) {
            }

        }

        //保存到相册中
        fun saveToCamera(context: Context, imagePath: String) {
            try {
                val params = imagePath.split(File.separator.toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                val title = params[params.size - 1]
                MediaStore.Images.Media.insertImage(context.contentResolver, imagePath, title, "")
                MediaScannerConnection.scanFile(context, arrayOf(imagePath), arrayOf("image/jpeg"), null)
            } catch (e: FileNotFoundException) {
                e.printStackTrace()
            }

        }

        /**
         * 创建新图片
         *
         * @param context {$}
         * @return 返回创建的图片文件对象
         */
        fun createPic(context: Context): File {
            val picName = System.currentTimeMillis().toString() + ".jpg"
            return IvyFileUtil.createNewFile(IvyFileUtil.getDownloadDir(context), picName)
        }

        fun createVideo(context: Context): File {
            val picName = System.currentTimeMillis().toString() + ".mp4"
            return IvyFileUtil.createNewFile(IvyFileUtil.getDownloadDir(context), picName)
        }

        fun getFile(context: Context, fileName: String): File? {
            val file = File(IvyFileUtil.getDownloadDir(context), fileName)
            return if (file.exists())
                file
            else
                null
        }

        fun createCachePic(context: Context): File {
            val picName = System.currentTimeMillis().toString() + ".jpg"
            return IvyFileUtil.createNewFile(IvyFileUtil.getCacheDir(context), picName)
        }

        fun deleteCacheImage(context: Context) {
            deleteDirWihtFile(IvyFileUtil.getCacheDir(context))
        }

        //删除文件夹和文件夹里面的文件
        fun deleteDir(pPath: String) {
            val dir = File(pPath)
            deleteDirWihtFile(dir)
        }

        fun deleteDirWihtFile(dir: File?) {
            if (dir == null || !dir.exists() || !dir.isDirectory)
                return
            for (file in dir.listFiles()) {
                if (file.isFile)
                    file.delete() // 删除所有文件
                else if (file.isDirectory)
                    deleteDirWihtFile(file) // 递规的方式删除文件夹
            }
            dir.delete()// 删除目录本身
        }

        /**
         * 创建拍照临时图片
         * @return 返回创建的图片文件对象
         */
        //    public static File createCacheCapturePic(Context context) {
        //        return createNewFile(getCacheFile(context), "image.jpg");
        //    }

        private fun getCacheCapturePic(context: Context): File {
            return File(IvyFileUtil.getCacheDir(context), "image.jpg")
        }

        fun clearAll(context: Context) {
            val bootFile = IvyFileUtil.createDirectory(context, IvyFileUtil.DIR_BOOT)
            if (!bootFile!!.exists())
                return
            deleteDir(bootFile)
        }

        fun deleteDir(dir: File?) {
            if (dir == null || !dir.exists() || !dir.isDirectory)
                return

            for (file in dir.listFiles()) {
                if (file.isFile)
                    file.delete() // 删除所有文件
                else if (file.isDirectory)
                    deleteDir(file) // 递规的方式删除文件夹
            }
            dir.delete()// 删除目录本身
        }


        fun saveBitmapToDCIM(context: Context, bitmap: Bitmap) {
            val file = saveBitmap(context, bitmap)
            try {
                // 把文件插入到系统图库
                MediaStore.Images.Media.insertImage(context.contentResolver, file!!.absolutePath, file.name, null)
                // 通知图库更新
                context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.parse("file://" + file.absolutePath)))
                Toast.makeText(context, "保存图片成功", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
            }

        }

        // 保存图片到手机指定目录
        fun savaImgFromBytes(context: Context, imgName: String, bytes: ByteArray) {
            if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                var filePath: String? = null
                var fos: FileOutputStream? = null
                try {
                    filePath = Environment.getExternalStorageDirectory().toString() + File.separator + IvyFileUtil.DIR_BOOT + File.separator + IvyFileUtil.DIR_DOWNLOAD
                    val imgDir = File(filePath)
                    if (!imgDir.exists()) {
                        imgDir.mkdirs()
                    }
                    filePath = filePath + File.separator + imgName
                    val imgfile = File(filePath)
                    if (!imgfile.exists())
                        imgfile.createNewFile()
                    fos = FileOutputStream(filePath)
                    fos.write(bytes)
                    fos.flush()
                    // 其次把文件插入到系统图库
                    try {
                        val file = File(filePath)
                        MediaStore.Images.Media.insertImage(context.contentResolver,
                                file.absolutePath, file.name, null)
                    } catch (e: FileNotFoundException) {
                        e.printStackTrace()
                    }

                    // 最后通知图库更新
                    val uri = Uri.parse("file://" + filePath)
                    context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri))
                    Toast.makeText(context, "图片已保存到" + filePath, Toast.LENGTH_SHORT).show()
                } catch (e: IOException) {
                    e.printStackTrace()
                } finally {
                    try {
                        if (fos != null) {
                            fos.close()
                        }
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }

                }
            } else {
                Toast.makeText(context, "请检查SD卡是否可用", Toast.LENGTH_SHORT).show()
            }
        }

        fun saveBitmap(context: Context, bitmap: Bitmap): File? {
            if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                val file = IvyFileUtil.createPic(context)
                val fos: FileOutputStream
                try {
                    fos = FileOutputStream(file)
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, fos)
                    fos.flush()
                    fos.close()
                    return file
                } catch (e: FileNotFoundException) {
                    e.printStackTrace()
                } catch (e: IOException) {
                    e.printStackTrace()
                }

            } else {
                Toast.makeText(context, "请检查SD卡是否可用", Toast.LENGTH_SHORT).show()
            }
            return null
        }

        /**
         * 根据文件路径拷贝文件
         *
         * @param src      源文件
         * @param destPath 目标文件路径
         * @return boolean 成功true、失败false
         */
        fun copyFile(src: File?, destPath: String?, newFileName: String): Boolean {
            if (src == null || destPath == null) {
                return false
            }
            val dest = File(destPath, newFileName)
            if (dest.exists()) {
                dest.delete() // delete file
            }
            try {
                dest.createNewFile()
            } catch (e: IOException) {
                e.printStackTrace()
            }

            var srcChannel: FileChannel? = null
            var dstChannel: FileChannel? = null

            try {
                srcChannel = FileInputStream(src).channel
                dstChannel = FileOutputStream(dest).channel
                srcChannel!!.transferTo(0, srcChannel.size(), dstChannel)
            } catch (e: FileNotFoundException) {
                e.printStackTrace()
                return false
            } catch (e: IOException) {
                e.printStackTrace()
                return false
            }

            try {
                srcChannel.close()
                dstChannel!!.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }

            return true
        }

        fun copyFile(srcPath: String?, destPath: String?): Boolean {
            if (srcPath == null || destPath == null) {
                return false
            }
            val srcFile = File(srcPath)
            return if (!srcFile.exists())
                false
            else
                copyFile(srcFile, destPath, "copy" + srcFile.name)
        }

        fun copyImageToCache(context: Context, srcPath: String?): Boolean {
            if (srcPath == null) {
                return false
            }
            val srcFile = File(srcPath)
            return if (!srcFile.exists())
                false
            else
                copyFile(srcFile, IvyFileUtil.getCacheDir(context).absolutePath, IvyFileUtil.getCacheCapturePic(context).name)
        }

        /**
         * 从assets目录中复制整个文件夹内容
         * @param  context  Context 使用CopyFiles类的Activity
         * @param  oldPath  String  原文件路径  如：/aa
         * @param  newPath  String  复制后路径  如：xx:/bb/cc
         */
        fun copyFilesFromAssets(context: Context, oldPath: String, newPath: String) {
            try {
                val fileNames = context.assets.list(oldPath)//获取assets目录下的所有文件及目录名
                if (fileNames!!.isNotEmpty()) {//如果是目录
                    val file = File(newPath)
                    file.mkdirs()//如果文件夹不存在，则递归
                    for (fileName in fileNames.orEmpty()) {
                        copyFilesFromAssets(context, oldPath + File.separator + fileName, newPath + File.separator + fileName)
                    }
                } else {//如果是文件
                    val `is` = context.assets.open(oldPath)
                    val fos = FileOutputStream(File(newPath))
                    val buffer = ByteArray(1024)
                    var byteCount = 0
                    while (((`is`.read(buffer)).also { byteCount = it }) != -1) {//循环从输入流读取 buffer字节
                        fos.write(buffer, 0, byteCount)//将读取的输入流写入到输出流
                    }
                    fos.flush()//刷新缓冲区
                    `is`.close()
                    fos.close()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

        }
    }


}

