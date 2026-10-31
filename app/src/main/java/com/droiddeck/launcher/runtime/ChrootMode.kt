package com.droiddeck.launcher.runtime

import android.content.Context
import com.droiddeck.launcher.core.RootShell
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File

object ChrootMode {
    const val BINARY = "libddchroot.so"

    /** ddchroot turns proot's link2symlink entries into hard links once, until something may add new ones. */
    private const val LINKS_STAMP = ".ddchroot-links-done"

    @JvmStatic
    fun linksMayHaveChanged(root: File?) {
        if (root != null) File(root, LINKS_STAMP).delete()
    }

    fun binary(context: Context): File = File(context.applicationInfo.nativeLibraryDir, BINARY)

    /** Only once su has answered in this process, so building a command never waits on a root prompt. */
    @JvmStatic
    fun active(context: Context): Boolean =
        SessionPrefs.rootChroot(context) && RootShell.known() && binary(context).isFile

    /** Blocks on the root prompt; call off the main thread. */
    fun prepare(context: Context) {
        if (SessionPrefs.rootChroot(context)) RootShell.available()
    }
}
