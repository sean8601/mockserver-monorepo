import { Component, type ErrorInfo, type ReactNode } from 'react';
import { Alert, AlertTitle, Box } from '@mui/material';

interface ErrorBoundaryProps {
  /** Short label for what failed, e.g. "detail pane". */
  label?: string;
  children: ReactNode;
}

interface ErrorBoundaryState {
  error: Error | null;
}

/**
 * Catches render-time exceptions in a subtree and shows an inline error instead
 * of unmounting the whole app. Used around panels that render *untrusted*
 * captured traffic (request/response bodies parsed client-side), where a
 * malformed body could otherwise throw and blank the entire view.
 *
 * Resets automatically when its `children` identity changes (callers key the
 * boundary on the selected item) so selecting a different, well-formed row
 * recovers without a manual retry.
 */
export default class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { error: null };

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    // Surface to the console for debugging; the inline Alert is the user-facing path.
    console.error(`ErrorBoundary${this.props.label ? ` (${this.props.label})` : ''} caught:`, error, info.componentStack);
  }

  render(): ReactNode {
    if (this.state.error) {
      return (
        <Box sx={{ p: 2 }}>
          <Alert severity="error" role="alert">
            <AlertTitle>Could not render {this.props.label ?? 'this content'}</AlertTitle>
            {this.state.error.message || 'An unexpected error occurred while rendering captured data.'}
          </Alert>
        </Box>
      );
    }
    return this.props.children;
  }
}
