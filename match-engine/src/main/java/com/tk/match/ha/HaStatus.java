package com.tk.match.ha;


import lombok.Getter;
import lombok.Setter;

public class HaStatus {

    @Setter
    @Getter
    private static volatile boolean isMaster = false;

}
