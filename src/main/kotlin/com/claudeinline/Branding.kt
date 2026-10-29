package com.claudeinline

import java.util.Locale
import java.util.ResourceBundle

/**
 * The product name used in every UI string. The values live in gradle.properties and reach this bundle through
 * processResources, so renaming the product never means touching Kotlin sources.
 */
object Branding {
    private val bundle: ResourceBundle =
        ResourceBundle.getBundle("messages.Branding", Locale.ROOT, Branding::class.java.classLoader)

    /** Full name, e.g. "Inline AI Prompt". */
    val NAME: String = bundle.getString("product.name")

    /** Short prefix for menus and undo entries, e.g. "Inline AI". */
    val SHORT: String = bundle.getString("product.short")

    /** Name of the per-project folder that holds the learned patterns. */
    val DIR: String = bundle.getString("product.dir")

    /** Internal id of the notification group registered in plugin.xml. */
    const val NOTIFICATION_GROUP_ID = "InlineAI.Notifications"
}
