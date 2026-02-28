package com.example.blackbox.logging

import android.annotation.SuppressLint

@SuppressLint("LogNotTimber")
object AppLog {
    fun d(tag: String, message: String): Int = android.util.Log.d(tag, message)

    fun i(tag: String, message: String): Int = android.util.Log.i(tag, message)

    fun w(tag: String, message: String): Int = android.util.Log.w(tag, message)

    fun w(tag: String, message: String, throwable: Throwable?): Int =
        if (throwable != null) android.util.Log.w(tag, message, throwable) else android.util.Log.w(tag, message)

    fun e(tag: String, message: String): Int = android.util.Log.e(tag, message)

    fun e(tag: String, message: String, throwable: Throwable?): Int =
        if (throwable != null) android.util.Log.e(tag, message, throwable) else android.util.Log.e(tag, message)
}
