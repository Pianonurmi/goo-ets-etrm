plugins {
    base
    id("org.jetbrains.kotlinx.kover")
}

kover {
    reports {
        total {
            filters {
                excludes {
                    packages("*.adapter.jooq.generated*")
                    classes("*Application*", "*Kt")
                }
            }
            // A ratchet (review finding C9): measured at 94 % of lines and 69 % of branches when the rule was set;
            // raise the bounds as coverage grows, never lower them.
            verify {
                rule("line-coverage") {
                    minBound(90, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE)
                }
                rule("branch-coverage") {
                    minBound(65, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH)
                }
            }
        }
    }
}
