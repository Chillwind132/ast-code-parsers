using System;
using System.Collections.Generic;

namespace Example.Orders
{
    public class TransactionalAttribute : Attribute
    {
        public bool ReadOnly { get; set; }
    }

    public class Promo
    {
        public decimal GetAmount() => 5.00m;
    }

    public class Order
    {
        public decimal GetTotal() => 100.00m;
    }

    public class PromoRepository
    {
        public Promo FindByCode(string code) => new Promo();
    }

    public abstract class BaseService
    {
        public abstract decimal ApplyDiscount(Order order, string code);

        protected void Validate(Promo promo)
        {
            if (promo == null) throw new ArgumentNullException(nameof(promo));
        }
    }

    public class OrderService : BaseService
    {
        private readonly PromoRepository _promoRepository = new PromoRepository();

        /// <summary>
        /// Applies a promotional discount to an order.
        /// </summary>
        /// <param name="order">the order to discount</param>
        /// <param name="code">the promo code to apply</param>
        /// <returns>the new total after discount</returns>
        [Transactional(ReadOnly = false)]
        public override decimal ApplyDiscount(Order order, string code)
        {
            Promo promo = _promoRepository.FindByCode(code);
            Validate(promo);
            return order.GetTotal() - promo.GetAmount();
        }
    }
}
