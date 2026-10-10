package com.pianonurmi.etrm.workflow

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Generic approval inbox. A leaf module: owning modules depend on its API, register their task types through
 * `TaskHandler`s and open tasks; workflow enforces roles, four-eyes, scope and deadlines.
 */
@PackageInfo
@ApplicationModule(displayName = "Workflow", allowedDependencies = [])
class WorkflowModule
