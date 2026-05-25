package com.gongu.server.domain.product.scheduler;

import com.gongu.server.domain.product.entity.Product;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ProductStatusTransactionHelper {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void activateOne(Product product) {
        product.activate();
    }
}
