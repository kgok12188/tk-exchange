package com.tk.futures.slot;

import com.lmax.disruptor.EventFactory;
import com.tk.futures.inbound.CommandMessage;
import lombok.Getter;
import lombok.Setter;

/**
 * Disruptor 事件封装：承载一条 CommandMessage。
 */
@Setter
@Getter
public class CommandMessageEvent {

    private CommandMessage message;

    public static final EventFactory<CommandMessageEvent> FACTORY = CommandMessageEvent::new;
}

