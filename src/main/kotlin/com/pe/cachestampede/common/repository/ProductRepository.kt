package com.pe.cachestampede.common.repository

import com.pe.cachestampede.common.domain.Product
import org.springframework.data.jpa.repository.JpaRepository

interface ProductRepository : JpaRepository<Product, Long>
