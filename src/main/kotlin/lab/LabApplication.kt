package lab

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class LabApplication

fun main(args: Array<String>) {
    runApplication<LabApplication>(*args)
}
