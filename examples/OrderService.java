package com.example.orders;

import java.util.List;

public class OrderService extends BaseService implements Auditable {

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
