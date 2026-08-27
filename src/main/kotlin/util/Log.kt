package util

import com.intellij.openapi.diagnostic.Logger

/** One shared logger for the whole plugin, so it has one category in idea.log */
val LOG: Logger = Logger.getInstance("myBitbucket")
