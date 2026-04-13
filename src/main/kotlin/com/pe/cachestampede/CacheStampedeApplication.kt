package com.pe.cachestampede

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class CacheStampedeApplication

fun main(args: Array<String>) {
    runApplication<CacheStampedeApplication>(*args)
}
