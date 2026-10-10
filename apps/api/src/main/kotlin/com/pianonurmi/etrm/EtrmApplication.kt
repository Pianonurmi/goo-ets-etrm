package com.pianonurmi.etrm

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.modulith.Modulithic
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Assembles all bounded-context modules into one deployable. Module boundaries are verified by
 * `ModularityTests` using Spring Modulith; `platform` is a shared kernel open to every module.
 */
@SpringBootApplication
@Modulithic(sharedModules = ["platform"], systemName = "GoO / EU ETS ETRM")
@EnableScheduling
class EtrmApplication

fun main(args: Array<String>) {
    runApplication<EtrmApplication>(*args)
}
