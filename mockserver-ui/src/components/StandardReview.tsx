import { useMemo, useState } from 'react';
import Box from '@mui/material/Box';
import Tabs from '@mui/material/Tabs';
import Tab from '@mui/material/Tab';
import {
  standardToJava,
  standardToJson,
  standardToCurl,
  type StandardActionPayload,
  type StandardMatcher,
} from '../lib/standardCodegen';
import CopyButton from './CopyButton';

export interface StandardReviewProps {
  matcher: StandardMatcher;
  action: StandardActionPayload;
  baseUrl: string;
}

/**
 * Java / JSON / curl preview for the standard-expectation Composer flow.
 * Mirrors the wizard's Step 3 review so both kinds give the user
 * copy-pasteable code before they register on the server.
 */
export default function StandardReview({ matcher, action, baseUrl }: StandardReviewProps) {
  const [tab, setTab] = useState(0);

  const javaCode = useMemo(() => standardToJava(matcher, action), [matcher, action]);
  const jsonCode = useMemo(() => standardToJson(matcher, action), [matcher, action]);
  const curlCode = useMemo(() => standardToCurl(matcher, action, baseUrl), [matcher, action, baseUrl]);

  const tabLabels = ['Java', 'JSON', 'curl'];
  const outputs = [javaCode, jsonCode, curlCode];
  const safeTab = Math.min(tab, tabLabels.length - 1);

  return (
    <Box sx={{ py: 1 }}>
      <Tabs
        value={safeTab}
        onChange={(_, v: number) => setTab(v)}
        sx={{ mb: 1, minHeight: 32, '& .MuiTab-root': { minHeight: 32, py: 0.5, fontSize: '0.8rem' } }}
      >
        {tabLabels.map((label) => (
          <Tab key={label} label={label} />
        ))}
      </Tabs>

      <Box sx={{ position: 'relative' }}>
        <Box sx={{ position: 'absolute', top: 4, right: 4, zIndex: 1 }}>
          <CopyButton text={outputs[safeTab]!} />
        </Box>
        <Box
          component="pre"
          sx={{
            fontFamily: 'monospace',
            fontSize: '0.75rem',
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-all',
            m: 0,
            p: 1.5,
            bgcolor: 'action.hover',
            borderRadius: 1,
            overflow: 'auto',
            maxHeight: 500,
          }}
        >
          {outputs[safeTab]}
        </Box>
      </Box>
    </Box>
  );
}
