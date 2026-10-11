import { Table, type TableScrollContainerProps } from '@mantine/core';

/**
 * A table that scrolls sideways below `minWidth`. The scrolling area takes keyboard focus and is a named region, so
 * keyboard users can scroll it too (axe `scrollable-region-focusable`).
 */
export function ScrollTable({
  label,
  ...props
}: Omit<TableScrollContainerProps, 'type' | 'scrollAreaProps'> & { label: string }) {
  return (
    <Table.ScrollContainer
      {...props}
      scrollAreaProps={{ viewportProps: { tabIndex: 0, role: 'region', 'aria-label': label } }}
    />
  );
}
