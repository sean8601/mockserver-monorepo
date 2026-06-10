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
  actionLabel: string;
  onAction: () => void;
}

function ActionCard({ icon, title, description, actionLabel, onAction }: ActionCardProps) {
  return (
    <Card
      variant="outlined"
      sx={{
        flex: '1 1 0',
        minWidth: 0,
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
      <CardActions sx={{ px: 2, pb: 2 }}>
        <Button size="small" variant="contained" onClick={onAction}>
          {actionLabel}
        </Button>
      </CardActions>
    </Card>
  );
}

const OTHER_TABS: { view: ViewMode; label: string; description: string }[] = [
  { view: 'dashboard', label: 'Dashboard', description: 'active mocks & live event log' },
  { view: 'library', label: 'Library', description: 'import / export, Postman, WSDL, HAR' },
  { view: 'verification', label: 'Verification', description: 'assert which requests were received' },
  { view: 'drift', label: 'Drift', description: 'spot when an upstream API changes' },
  { view: 'async', label: 'Async', description: 'Kafka / MQTT / AMQP broker mocking' },
  { view: 'metrics', label: 'Metrics', description: 'Prometheus stats' },
];

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
          flexWrap: 'nowrap',
          gap: 2,
          alignItems: 'stretch',
          justifyContent: 'center',
          width: '100%',
          maxWidth: 1200,
        }}
      >
        <ActionCard
          icon={<PauseCircleIcon color="primary" />}
          title="Breakpoints"
          description="Pause requests and responses mid-flight — proxied, mocked, or unmatched — then inspect, edit, continue, or abort them."
          actionLabel="Open Breakpoints"
          onAction={go('breakpoints')}
        />

        <ActionCard
          icon={<SwapHorizIcon color="primary" />}
          title="Debugging Proxy"
          description="Sit MockServer between your app and a real API to record, inspect, and replay live traffic — and validate or rewrite it on the way through."
          actionLabel="View Traffic"
          onAction={go('traffic')}
        />

        <ActionCard
          icon={<SmartToyIcon color="primary" />}
          title="LLM / AI Debugging"
          description="Mock LLM providers like OpenAI and Anthropic, and inspect agent runs — conversations, tool calls, tokens, and cost — grouped by session."
          actionLabel="Open Sessions"
          onAction={go('sessions')}
        />

        <ActionCard
          icon={<UploadFileIcon color="primary" />}
          title="Mocking"
          description="Build mock responses by hand, or import an OpenAPI / Swagger spec, Postman collection, WSDL, or HAR file to generate stubs automatically."
          actionLabel="Import OpenAPI"
          onAction={() => setOpenApiOpen(true)}
        />

        <ActionCard
          icon={<BoltIcon color="primary" />}
          title="Chaos Testing"
          description="Inject latency, errors, and dropped connections to test how your system copes when the APIs it depends on misbehave."
          actionLabel="Open Chaos"
          onAction={go('chaos')}
        />
      </Box>

      <Box sx={{ mt: 4, maxWidth: 760, width: '100%' }}>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
          More in the tabs above (or see{' '}
          <Link
            href="https://www.mock-server.com/mock_server/mockserver_ui.html"
            target="_blank"
            rel="noopener"
          >
            UI docs
          </Link>
          ):
        </Typography>
        <Box component="ul" sx={{ m: 0, pl: 3 }}>
          {OTHER_TABS.map((tab) => (
            <Box component="li" key={tab.view} sx={{ mb: 0.5 }}>
              <Link component="button" type="button" onClick={go(tab.view)} sx={{ verticalAlign: 'baseline' }}>
                {tab.label}
              </Link>
              <Typography component="span" variant="body2" color="text.secondary">
                {' '}— {tab.description}
              </Typography>
            </Box>
          ))}
        </Box>
      </Box>

      <OpenApiImportDialog
        open={openApiOpen}
        onClose={() => setOpenApiOpen(false)}
        connectionParams={connectionParams}
      />
    </Box>
  );
}
