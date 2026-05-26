import JsonView from '@uiw/react-json-view';
import { darkTheme } from '@uiw/react-json-view/dark';
import { lightTheme } from '@uiw/react-json-view/light';
import Box from '@mui/material/Box';
import { useDashboardStore } from '../store';
import CopyButton from './CopyButton';

interface JsonViewerProps {
  data: Record<string, unknown> | unknown[];
  collapsed?: number;
  enableClipboard?: boolean;
}

export default function JsonViewer({
  data,
  collapsed = 1,
  enableClipboard = true,
}: JsonViewerProps) {
  const themeMode = useDashboardStore((s) => s.themeMode);

  return (
    <Box
      sx={{
        position: 'relative',
        width: '100%',
        // `min-width: 0` lets the wrapper shrink below its content's natural
        // width when it lives inside a flex/narrow container — without this
        // long string values overflow and get clipped by the parent bubble.
        minWidth: 0,
        // Force long string values to wrap rather than extend off-screen.
        // `@uiw/react-json-view` renders values in inline spans which by
        // default sit on the same line as the key.
        '& span, & div': { wordBreak: 'break-word' },
      }}
    >
      {enableClipboard && (
        <Box className="copy-btn" sx={{ position: 'absolute', top: 0, right: 0, zIndex: 1, opacity: 0 }}>
          <CopyButton text={JSON.stringify(data, null, 2)} />
        </Box>
      )}
      <JsonView
        value={data as object}
        style={themeMode === 'dark' ? darkTheme : lightTheme}
        collapsed={collapsed}
        displayObjectSize={false}
        displayDataTypes={false}
        enableClipboard={false}
      />
    </Box>
  );
}
