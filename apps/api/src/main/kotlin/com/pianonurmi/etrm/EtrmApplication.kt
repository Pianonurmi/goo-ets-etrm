package com.pianonurmi.etrm

import com.pianonurmi.etrm.config.MigrateOnly
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.modulith.Modulithic
import org.springframework.scheduling.annotation.EnableScheduling
import kotlin.system.exitProcess

/**
 * Assembles all bounded-context modules into one deployable. Module boundaries are verified by
 * `ModularityTests` using Spring Modulith; `platform` is a shared kernel open to every module.
 */
@SpringBootApplication
@Modulithic(sharedModules = ["platform"], systemName = "GoO / EU ETS ETRM")
@EnableScheduling
class EtrmApplication

/** Serves the application; with `--migrate-only` it migrates the database and exits ([MigrateOnly]). */
fun main(args: Array<String>) {
    if (MigrateOnly.requested(args)) exitProcess(MigrateOnly.run(args))
    runApplication<EtrmApplication>(*args)
}
