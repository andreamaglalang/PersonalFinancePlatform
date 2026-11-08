package com.elect3.mobdev.personalfinance.data.mock

import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Collections
import java.util.UUID

object TransactionFixtures {
    private enum class Category(val id: Int, val type: TransactionType) {
        Salary(1, TransactionType.Income),
        Freelance(2, TransactionType.Income),
        Food(3, TransactionType.Expense),
        Transport(4, TransactionType.Expense),
        Utilities(5, TransactionType.Expense),
        Shopping(6, TransactionType.Expense),
        Healthcare(7, TransactionType.Expense),
    }

    /** Fixed synthetic records, ordered by date descending then transaction ID descending. */
    val items: List<Transaction> = Collections.unmodifiableList(
        listOf(
            entry(45, "2026-11-07", Category.Food, "185.50", "Lunch at the campus canteen"),
            entry(44, "2026-11-07", Category.Transport, "35.00", null),
            entry(43, "2026-11-06", Category.Freelance, "3500.75", "Poster design payment"),
            entry(42, "2026-11-06", Category.Shopping, "499.00", "School supplies"),
            entry(41, "2026-11-05", Category.Utilities, "1250.00", "Electricity bill", 101, "2026-11-05"),
            entry(40, "2026-11-05", Category.Food, "320.25", "Groceries"),
            entry(39, "2026-11-04", Category.Healthcare, "275.00", "Prescription medicine"),
            entry(38, "2026-11-04", Category.Transport, "70.00", "Round-trip bus fare"),
            entry(37, "2026-11-03", Category.Food, "95.00", null),
            entry(36, "2026-11-03", Category.Shopping, "650.00", "Replacement backpack"),
            entry(35, "2026-11-02", Category.Utilities, "599.00", "Mobile data plan", 102, "2026-11-02"),
            entry(34, "2026-11-02", Category.Food, "210.50", "Dinner ingredients"),
            entry(33, "2026-11-01", Category.Salary, "25000.00", "November salary"),
            entry(32, "2026-11-01", Category.Transport, "120.00", "Ride home with groceries"),
            entry(31, "2026-10-31", Category.Food, "180.00", "Weekend lunch"),
            entry(30, "2026-10-31", Category.Freelance, "2200.00", "Website illustration payment"),
            entry(29, "2026-10-30", Category.Healthcare, "500.00", "Clinic consultation"),
            entry(28, "2026-10-30", Category.Transport, "35.00", null),
            entry(27, "2026-10-29", Category.Food, "450.75", "Weekly vegetables and fruit"),
            entry(26, "2026-10-29", Category.Shopping, "199.00", "Notebook and pens"),
            entry(25, "2026-10-28", Category.Utilities, "850.00", "Internet bill", 103, "2026-10-28"),
            entry(24, "2026-10-28", Category.Food, "89.50", "Breakfast"),
            entry(23, "2026-10-27", Category.Transport, "60.00", "Jeepney fares"),
            entry(22, "2026-10-27", Category.Food, "150.00", null),
            entry(21, "2026-10-26", Category.Freelance, "1800.00", "Photo editing payment"),
            entry(20, "2026-10-26", Category.Shopping, "399.00", "USB keyboard"),
            entry(19, "2026-10-25", Category.Healthcare, "145.50", "Vitamins"),
            entry(18, "2026-10-25", Category.Food, "260.00", "Family merienda"),
            entry(17, "2026-10-24", Category.Transport, "80.00", "Bus fare to library"),
            entry(16, "2026-10-24", Category.Food, "110.00", "Lunch after class"),
            entry(15, "2026-10-23", Category.Utilities, "325.00", "Water bill", 104, "2026-10-23"),
            entry(14, "2026-10-23", Category.Shopping, "249.75", "Household cleaning supplies"),
            entry(13, "2026-10-22", Category.Food, "175.00", null),
            entry(12, "2026-10-22", Category.Transport, "35.00", "Jeepney to campus"),
            entry(11, "2026-10-21", Category.Freelance, "1250.00", "Presentation layout payment"),
            entry(10, "2026-10-21", Category.Healthcare, "220.00", "First-aid supplies"),
            entry(9, "2026-10-20", Category.Food, "380.50", "Rice and pantry staples"),
            entry(8, "2026-10-20", Category.Shopping, "129.00", "Phone charging cable"),
            entry(7, "2026-10-19", Category.Transport, "90.00", "Weekend bus fares"),
            entry(6, "2026-10-19", Category.Food, "135.25", "Dinner"),
            entry(5, "2026-10-18", Category.Utilities, "150.00", "Prepaid mobile load"),
            entry(4, "2026-10-18", Category.Food, "75.00", null),
            entry(3, "2026-10-17", Category.Healthcare, "350.00", "Dental cleaning supplies"),
            entry(2, "2026-10-17", Category.Transport, "70.00", "Bus fares"),
            entry(1, "2026-10-16", Category.Salary, "12500.00", "Part-time salary"),
        ).sortedWith(compareByDescending<Transaction> { it.transactionDate }.thenByDescending { it.transactionId }),
    )

    private fun entry(
        id: Int,
        date: String,
        category: Category,
        amount: String,
        description: String?,
        billId: Int? = null,
        billDueDate: String? = null,
    ): Transaction {
        val money = BigDecimal(amount)
        val transactionDate = LocalDate.parse(date)
        return Transaction(
            transactionId = id,
            clientId = UUID.fromString("00000000-0000-4000-8000-${id.toString().padStart(12, '0')}"),
            type = category.type,
            categoryId = category.id,
            categoryName = category.name,
            amount = money,
            currencyCode = "PHP",
            fxRate = BigDecimal("1.00000000"),
            fxRateDate = transactionDate,
            amountBase = money,
            transactionDate = transactionDate,
            description = description,
            createdAtUtc = Instant.parse("${date}T12:00:00Z"),
            billId = billId,
            billDueDate = billDueDate?.let { LocalDate.parse(it) },
        )
    }
}
