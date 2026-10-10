package com.pianonurmi.etrm.taxonomy

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/** Taxonomy: hierarchical code sets. Depends on nothing but the shared platform kernel. */
@PackageInfo
@ApplicationModule(displayName = "Taxonomy", allowedDependencies = [])
class TaxonomyModule
