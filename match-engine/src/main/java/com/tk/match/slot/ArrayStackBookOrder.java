package com.tk.match.slot;

import com.tk.match.engine.BookOrder;

public class ArrayStackBookOrder {

    private int count;
    private final BookOrder[] bookOrders;

    public ArrayStackBookOrder(int fixedSize) {
        this.bookOrders = new BookOrder[fixedSize];
        this.count = 0;
    }

    public void add(BookOrder element) {
        if (count != bookOrders.length) {
            bookOrders[count] = element;
            count++;
        }
    }

    public BookOrder pop() {
        if (count != 0) {
            count--;
            BookOrder object = bookOrders[count];
            bookOrders[count] = null;
            return object;
        }
        return new BookOrder();
    }

}
