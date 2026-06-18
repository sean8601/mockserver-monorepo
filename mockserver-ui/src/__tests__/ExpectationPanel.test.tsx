import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ExpectationPanel from '../components/ExpectationPanel';
import { useDashboardStore } from '../store';
import * as expectationsLib from '../lib/expectations';

describe('ExpectationPanel', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      activeExpectations: [],
      expectationSearch: '',
      notification: null,
      pendingEditExpectation: null,
      view: 'dashboard',
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('shows empty state when no expectations', () => {
    render(<ExpectationPanel />);
    expect(screen.getByText(/No active expectations/)).toBeInTheDocument();
  });

  it('renders expectations with descriptions', () => {
    useDashboardStore.setState({
      activeExpectations: [
        {
          key: 'exp1',
          description: 'GET /api/users',
          value: { httpRequest: { method: 'GET', path: '/api/users' } },
        },
      ],
    });

    render(<ExpectationPanel />);
    // After the layout refactor the method and path render in separate
    // elements so the path can be right-aligned while the method stays left.
    expect(screen.getByText('GET')).toBeInTheDocument();
    expect(screen.getByText('/api/users')).toBeInTheDocument();
  });

  it('filters expectations by search', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({
      activeExpectations: [
        { key: 'exp1', description: 'GET /users', value: { httpRequest: { method: 'GET', path: '/users' } } },
        { key: 'exp2', description: 'POST /orders', value: { httpRequest: { method: 'POST', path: '/orders' } } },
      ],
    });

    render(<ExpectationPanel />);
    const searchInput = screen.getByPlaceholderText('Search...');
    await user.type(searchInput, 'orders');

    expect(screen.queryByText('/users')).not.toBeInTheDocument();
    expect(screen.getByText('/orders')).toBeInTheDocument();
  });

  it('shows count badge with correct number', () => {
    useDashboardStore.setState({
      activeExpectations: [
        { key: 'exp1', value: {} },
        { key: 'exp2', value: {} },
        { key: 'exp3', value: {} },
      ],
    });

    render(<ExpectationPanel />);
    const chip = document.querySelector('.MuiChip-label');
    expect(chip).toHaveTextContent('3');
  });

  it('Edit loads the expectation into the Composer and switches view', async () => {
    const user = userEvent.setup();
    const value = { id: 'exp-edit', httpRequest: { method: 'GET', path: '/edit' }, httpResponse: { statusCode: 200 } };
    useDashboardStore.setState({ activeExpectations: [{ key: 'exp-edit', value }] });

    render(<ExpectationPanel />);
    await user.click(screen.getByLabelText('Edit expectation'));

    const state = useDashboardStore.getState();
    expect(state.pendingEditExpectation).toEqual(value);
    expect(state.view).toBe('composer');
  });

  it('Delete asks for confirmation, calls deleteExpectation, drops the row and notifies', async () => {
    const user = userEvent.setup();
    const del = vi.spyOn(expectationsLib, 'deleteExpectation').mockResolvedValue(undefined);
    const value = { id: 'exp-del', httpRequest: { method: 'GET', path: '/del' } };
    useDashboardStore.setState({ activeExpectations: [{ key: 'exp-del', value }] });

    render(<ExpectationPanel />);
    await user.click(screen.getByLabelText('Delete expectation'));

    // Confirmation dialog appears; the lib must not be called until confirmed.
    expect(screen.getByText('Delete this expectation?')).toBeInTheDocument();
    expect(del).not.toHaveBeenCalled();

    await user.click(screen.getByRole('button', { name: 'Delete' }));

    await waitFor(() => expect(del).toHaveBeenCalledTimes(1));
    expect(del.mock.calls[0]![1]).toBe('exp-del');

    await waitFor(() => {
      expect(useDashboardStore.getState().activeExpectations).toHaveLength(0);
    });
    const note = useDashboardStore.getState().notification;
    expect(note).toMatchObject({ severity: 'success' });
    expect(note!.message).toContain('exp-del');
  });

  it('Delete can be cancelled without calling the server', async () => {
    const user = userEvent.setup();
    const del = vi.spyOn(expectationsLib, 'deleteExpectation').mockResolvedValue(undefined);
    useDashboardStore.setState({
      activeExpectations: [{ key: 'exp-keep', value: { id: 'exp-keep', httpRequest: { method: 'GET', path: '/keep' } } }],
    });

    render(<ExpectationPanel />);
    await user.click(screen.getByLabelText('Delete expectation'));
    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(del).not.toHaveBeenCalled();
    expect(useDashboardStore.getState().activeExpectations).toHaveLength(1);
  });

  it('surfaces a humanized error notification when the delete fails', async () => {
    const user = userEvent.setup();
    vi.spyOn(expectationsLib, 'deleteExpectation').mockRejectedValue(new Error('boom'));
    useDashboardStore.setState({
      activeExpectations: [{ key: 'exp-err', value: { id: 'exp-err', httpRequest: { method: 'GET', path: '/err' } } }],
    });

    render(<ExpectationPanel />);
    await user.click(screen.getByLabelText('Delete expectation'));
    await user.click(screen.getByRole('button', { name: 'Delete' }));

    await waitFor(() => {
      expect(useDashboardStore.getState().notification).toMatchObject({ severity: 'error' });
    });
    // The row is kept on failure.
    expect(useDashboardStore.getState().activeExpectations).toHaveLength(1);
  });
});
