package com.pianonurmi.etrm.workflow

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/** Approval inbox with segregation of duties for trades and allocation plans. */
@PackageInfo
@ApplicationModule(displayName = "Workflow", allowedDependencies = ["trade :: api", "allocation :: api"])
class WorkflowModule
