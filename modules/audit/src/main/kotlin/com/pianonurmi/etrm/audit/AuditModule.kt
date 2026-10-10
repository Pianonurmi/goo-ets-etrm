package com.pianonurmi.etrm.audit

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/** Audit trail of domain events and commands. Listens to every module through the shared event contract only. */
@PackageInfo
@ApplicationModule(displayName = "Audit", allowedDependencies = [])
class AuditModule
