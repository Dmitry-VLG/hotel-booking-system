package com.example.booking;

import com.example.booking.BookingTransactions.Snapshot;
import com.example.common.InventoryApi;
import java.util.List;
import java.util.Set;

public interface HotelInventory {
    List<InventoryApi.Candidate> recommend(Snapshot booking, Set<Long> excluded);

    InventoryApi.Reservation hold(Snapshot booking);

    InventoryApi.Reservation commit(Snapshot booking);

    InventoryApi.Reservation release(Snapshot booking);
}
