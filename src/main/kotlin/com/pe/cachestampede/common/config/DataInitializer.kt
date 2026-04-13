package com.pe.cachestampede.common.config

import com.pe.cachestampede.common.domain.Product
import com.pe.cachestampede.common.repository.ProductRepository
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

@Component
class DataInitializer(
    private val productRepository: ProductRepository
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        if (productRepository.count() > 0) return

        val products = (1..10).map { i ->
            Product(
                name = "Product $i",
                price = i * 1000,
                stock = i * 10
            )
        }
        productRepository.saveAll(products)
    }
}
