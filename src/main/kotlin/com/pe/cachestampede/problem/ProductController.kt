package com.pe.cachestampede.problem

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/problem")
class ProductController(
    private val productService: ProductService
) {

    @GetMapping("/products/{id}")
    fun getProduct(@PathVariable id: Long): ResponseEntity<*> {
        val product = productService.getProduct(id) ?: return ResponseEntity.notFound().build<Any>()
        return ResponseEntity.ok(product)
    }
}
