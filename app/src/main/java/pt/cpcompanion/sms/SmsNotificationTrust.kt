package pt.cpcompanion.sms

/** Only the user-selected SMS application may supply automatic ticket notifications. */
internal fun isTrustedSmsNotificationPackage(
    packageName: String,
    defaultSmsPackage: String?,
): Boolean = !defaultSmsPackage.isNullOrBlank() && packageName == defaultSmsPackage
