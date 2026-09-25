package moni

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import java.util.*

@SpringBootApplication
class MoniApplication

fun main(args: Array<String>) {
	runApplication<MoniApplication>(*args)
}
