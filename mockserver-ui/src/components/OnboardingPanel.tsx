import Box from '@mui/material/Box';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import CardActions from '@mui/material/CardActions';
import Typography from '@mui/material/Typography';
import Button from '@mui/material/Button';
import Link from '@mui/material/Link';
import UploadFileIcon from '@mui/icons-material/UploadFile';
import SwapHorizIcon from '@mui/icons-material/SwapHoriz';
import PauseCircleIcon from '@mui/icons-material/PauseCircle';
import SmartToyIcon from '@mui/icons-material/SmartToy';
import BoltIcon from '@mui/icons-material/Bolt';
import { useState } from 'react';
import OpenApiImportDialog from './OpenApiImportDialog';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import { useDashboardStore, type ViewMode } from '../store';

interface OnboardingPanelProps {
  connectionParams: ConnectionParams;
}

interface ActionCardProps {
  icon: React.ReactNode;
  title: string;
  description: string;
  action: React.ReactNode;
}

function ActionCard({ icon, title, description, action }: ActionCardProps) {
  return (
    <Card
      variant="outlined"
      sx={{
        flex: '1 1 260px',
        maxWidth: 340,
        display: 'flex',
        flexDirection: 'column',
      }}
    >
      <CardContent sx={{ flex: 1 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
          {icon}
          <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
            {title}
          </Typography>
        </Box>
        <Typography variant="body2" color="text.secondary">
          {description}
        </Typography>
      </CardContent>
      <CardActions sx={{ px: 2, pb: 2, gap: 1, flexWrap: 'wrap' }}>{action}</CardActions>
    </Card>
  );
}

export default function OnboardingPanel({ connectionParams }: OnboardingPanelProps) {
  const [openApiOpen, setOpenApiOpen] = useState(false);
  const setView = useDashboardStore((s) => s.setView);

  const go = (view: ViewMode) => () => setView(view);

  return (
    <Box
      sx={{
        flex: 1,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        p: 4,
        overflow: 'auto',
      }}
    >
      <Typography variant="h5" gutterBottom sx={{ fontWeight: 700 }}>
        Welcome to MockServer
      </Typography>
      <Typography
        variant="body1"
        color="text.secondary"
        sx={{ mb: 4, maxWidth: 640, textAlign: 'center' }}
      >
        Mock, proxy, and debug HTTP, HTTPS, and other APIs. These are the key
        things you can do — open any one below, or use the tabs above for the
        full feature set.
      </Typography>

      <Box
        sx={{
          display: 'flex',
          flexWrap: 'wrap',
          gap: 2,
          justifyContent: 'center',
          maxWidth: 1100,
        }}
      >
        <ActionCard
          icon={<PauseCircleIcon color="primary" />}
          title="Breakpoints & Request/Response Editing"
          description="Pause requests and responses mid-flight — proxied, mocked, or unmatched — then inspect, edit, continue, or abort them. Like browser DevTools for any API."
          action={
            <Button size="small" variant="contained" onClick={go('breakpoints')}>
              Open Breakpoints
            </Button>
          }
        />

        <ActionCard
          icon={<SwapHorizIcon color="primary" />}
          title="Debugging Proxy"
          description="Sit MockServer between your app and a real API to record, inspect, and replay live traffic — and validate or rewrite it on the way through."
          action={
            <>
              <Button size="small" variant="contained" onClick={go('traffic')}>
                View Traffic
              </Button>
              <Button
                size="small"
                variant="text"
                href="https://www.mock-server.com/proxy/debugging_proxied_traffic.html"
                target="_blank"
                rel="noopener"
              >
                Proxy guide
              </Button>
            </>
          }
        />

        <ActionCard
          icon={<SmartToyIcon color="primary" />}
          title="LLM / AI Debugging"
          description="Mock LLM providers like OpenAI and Anthropic, and inspect agent runs — conversations, tool calls, tokens, and cost — grouped by session."
          action={
            <Button size="small" variant="contained" onClick={go('sessions')}>
              Open Sessions
            </Button>
          }
        />

        <ActionCard
          icon={<UploadFileIcon color="primary" />}
          title="Mocking"
          description="Build mock responses by hand, or import an OpenAPI / Swagger spec, Postman collection, WSDL, or HAR file to generate stubs automatically."
          action={
            <>
              <Button
                size="small"
                variant="contained"
                startIcon={<UploadFileIcon />}
                onClick={() => setOpenApiOpen(true)}
              >
                Import OpenAPI
              </Button>
              <Button size="small" variant="text" onClick={go('composer')}>
                Create a mock
              </Button>
            </>
          }
        />

        <ActionCard
          icon={<BoltIcon color="primary" />}
          title="Chaos Testing"
          description="Inject latency, errors, and dropped connections to test how your system copes when the APIs it depends on misbehave."
          action={
            <Button size="small" variant="contained" onClick={go('chaos')}>
              Open Chaos
            </Button>
          }
        />
      </Box>

      <Typography
        variant="body2"
        color="text.secondary"
        sx={{ mt: 4, maxWidth: 760, textAlign: 'center' }}
      >
        More in the tabs above:{' '}
        <Box component="span" sx={{ fontWeight: 600 }}>Dashboard</Box> (active mocks &amp; live event log),{' '}
        <Box component="span" sx={{ fontWeight: 600 }}>Library</Box> (import / export, Postman, WSDL, HAR),{' '}
        <Box component="span" sx={{ fontWeight: 600 }}>Verification</Box> (assert which requests were received),{' '}
        <Box component="span" sx={{ fontWeight: 600 }}>Drift</Box> (spot when an upstream API changes),{' '}
        <Box component="span" sx={{ fontWeight: 600 }}>Async</Box> (Kafka / MQTT / AMQP broker mocking), and{' '}
        <Box component="span" sx={{ fontWeight: 600 }}>Metrics</Box> (Prometheus stats).{' '}
        <Link
          href="https://www.mock-server.com/mock_server/mockserver_ui.html"
          target="_blank"
          rel="noopener"
        >
          Dashboard docs
        </Link>
      </Typography>

      <OpenApiImportDialog
        open={openApiOpen}
        onClose={() => setOpenApiOpen(false)}
        connectionParams={connectionParams}
      />
    </Box>
  );
}
