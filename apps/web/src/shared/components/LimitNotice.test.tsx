import { MantineProvider } from '@mantine/core';
import { render, screen } from '@testing-library/react';
import { LimitNotice, reachedLimit } from './LimitNotice';

describe('LimitNotice', () => {
  it('knows when a list was cut off', () => {
    expect(reachedLimit(2000, 2000)).toBe(true);
    expect(reachedLimit(2001, 2000)).toBe(true);
    expect(reachedLimit(1999, 2000)).toBe(false);
    expect(reachedLimit(0, 0)).toBe(false);
  });

  it('says how many rows are shown when the limit was reached', () => {
    render(
      <MantineProvider>
        <LimitNotice shown={2000} limit={2000} />
      </MantineProvider>,
    );
    expect(screen.getByTestId('limit-notice')).toHaveTextContent('Showing the first 2,000 rows');
  });

  it('shows nothing below the limit', () => {
    render(
      <MantineProvider>
        <LimitNotice shown={1999} limit={2000} />
      </MantineProvider>,
    );
    expect(screen.queryByTestId('limit-notice')).not.toBeInTheDocument();
  });
});
