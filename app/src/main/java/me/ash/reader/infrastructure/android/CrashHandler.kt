package me.ash.reader.infrastructure.android

import android.content.Context
import android.content.Intent
import android.os.Looper
import timber.log.Timber
import me.ash.reader.R
import me.ash.reader.ui.ext.showToastLong
import java.lang.Thread.UncaughtExceptionHandler
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The uncaught exception handler for the application.
 */
class CrashHandler(private val context: Context) : UncaughtExceptionHandler {

    init {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    /**
     * Catch all uncaught exception and log it.
     */
    override fun uncaughtException(p0: Thread, p1: Throwable) {
        val causeMessage = getCauseMessage(p1)
        Timber.tag("RLog").e(p1, "uncaughtException: $causeMessage")

        val rootCause = getRootCause(p1)
        if (isNetworkException(rootCause)) {
            showToastOnMainThread(context.getString(R.string.server_unreachable))
            return
        }

        context.startActivity(Intent(context, CrashReportActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(CrashReportActivity.ERROR_REPORT_KEY, p1.stackTraceToString())
        })
    }

    private fun isNetworkException(throwable: Throwable): Boolean {
        return throwable is ConnectException ||
                throwable is UnknownHostException ||
                throwable is SocketTimeoutException ||
                throwable is SocketException ||
                throwable is NoRouteToHostException
    }

    private fun showToastOnMainThread(message: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            context.showToastLong(message)
        } else {
            android.os.Handler(Looper.getMainLooper()).post {
                context.showToastLong(message)
            }
        }
    }

    private tailrec fun getRootCause(throwable: Throwable): Throwable {
        val cause = throwable.cause
        return if (cause == null) throwable else getRootCause(cause)
    }

    private fun getCauseMessage(e: Throwable?): String? {
        val cause = getCauseRecursively(e)
        return if (cause != null) cause.message.toString() else e?.javaClass?.name
    }

    private fun getCauseRecursively(e: Throwable?): Throwable? {
        var cause: Throwable?
        cause = e
        while (cause?.cause != null && cause !is RuntimeException) {
            cause = cause.cause
        }
        return cause
    }
}
