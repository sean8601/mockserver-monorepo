import { createContext } from 'react';

/**
 * The scroll container element of the enclosing Panel, published so a
 * VirtualList rendered as the Panel's content can use it as the virtualizer's
 * scroll element without Panel having to know about virtualization. Null until
 * the Panel's scroll box has mounted.
 */
export const PanelScrollContext = createContext<HTMLElement | null>(null);
