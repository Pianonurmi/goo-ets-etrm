package com.pianonurmi.etrm.platform

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/** Shared kernel: value types, events, errors, security context. Open so every module may use any of its packages. */
@PackageInfo
@ApplicationModule(displayName = "Platform", type = ApplicationModule.Type.OPEN)
class PlatformModule
