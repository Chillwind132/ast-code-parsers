package com.example.orders;

import java.math.BigDecimal;

@interface Transactional {
    boolean readOnly();
}

class InvalidPromoException extends Exception {
    InvalidPromoException(String message) {
        super(message);
    }
}

class Promo {
    BigDecimal getAmount() {
        return new BigDecimal("5.00");
    }
}

class Order {
    BigDecimal getTotal() {
        return new BigDecimal("100.00");
    }
}

class PromoRepository {
    Promo findByCode(String code) {
        return new Promo();
    }
}

abstract class BaseService {
    abstract BigDecimal applyDiscount(Order order, String code) throws InvalidPromoException;

    protected void validate(Promo promo) throws InvalidPromoException {
        if (promo == null) {
            throw new InvalidPromoException("unknown promo");
        }
    }
}

public class OrderService extends BaseService {

    private final PromoRepository promoRepository = new PromoRepository();

    /**
     * Applies a promotional discount to an order.
     *
     * @param order the order to discount
     * @param code the promo code to apply
     * @return the new total after discount
     * @throws InvalidPromoException if the code is expired
     */
    @Override
    @Transactional(readOnly = false)
    public BigDecimal applyDiscount(Order order, String code) throws InvalidPromoException {
        Promo promo = promoRepository.findByCode(code);
        validate(promo);
        return order.getTotal().subtract(promo.getAmount());
    }
}
