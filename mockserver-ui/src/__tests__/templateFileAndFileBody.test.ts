/**
 * Tests for the template-file and templated-file-body composer features:
 * - httpResponseTemplate / httpForwardTemplate `templateFile` (load a template from a file)
 * - httpResponse FILE body with `templateType` (render an external body file as a template)
 *
 * Each test asserts the emitted JSON shape matches the MockServer Java DTOs
 * (HttpTemplateDTO.templateFile, FileBodyDTO.templateType).
 */
import { describe, it, expect } from 'vitest';
import {
  buildExpectationJson,
  type StandardMatcher,
  type StandardActionPayload,
} from '../lib/standardCodegen';

function baseMatcher(overrides?: Partial<StandardMatcher>): StandardMatcher {
  return {
    id: '',
    method: 'GET',
    path: '/api/test',
    headers: '',
    queryString: '',
    cookies: '',
    pathParams: '',
    body: '',
    bodyBinary: false,
    bodyMatcherType: 'string',
    secure: false,
    priority: 0,
    times: 0,
    ...overrides,
  };
}

describe('buildExpectationJson response template with templateFile', () => {
  it('emits templateFile and omits empty inline template', () => {
    const action: StandardActionPayload = {
      type: 'template',
      template: { templateType: 'MUSTACHE', template: '', templateFile: 'templates/r.mustache' },
    };
    const t = buildExpectationJson(baseMatcher(), action)['httpResponseTemplate'] as Record<string, unknown>;
    expect(t['templateType']).toBe('MUSTACHE');
    expect(t['templateFile']).toBe('templates/r.mustache');
    expect(t).not.toHaveProperty('template');
  });

  it('keeps the inline template when both are provided (inline wins)', () => {
    const action: StandardActionPayload = {
      type: 'template',
      template: { templateType: 'VELOCITY', template: '{ "statusCode": 200 }', templateFile: 'templates/r.vm' },
    };
    const t = buildExpectationJson(baseMatcher(), action)['httpResponseTemplate'] as Record<string, unknown>;
    expect(t['template']).toBe('{ "statusCode": 200 }');
    expect(t['templateFile']).toBe('templates/r.vm');
  });

  it('emits only inline template when no templateFile is set (unchanged behaviour)', () => {
    const action: StandardActionPayload = {
      type: 'template',
      template: { templateType: 'VELOCITY', template: '{ "statusCode": 200 }' },
    };
    const t = buildExpectationJson(baseMatcher(), action)['httpResponseTemplate'] as Record<string, unknown>;
    expect(t['template']).toBe('{ "statusCode": 200 }');
    expect(t).not.toHaveProperty('templateFile');
  });
});

describe('buildExpectationJson forward template with templateFile', () => {
  it('emits templateFile on httpForwardTemplate', () => {
    const action: StandardActionPayload = {
      type: 'forward_template',
      forwardTemplate: { templateType: 'MUSTACHE', template: '', templateFile: 'templates/f.mustache' },
    };
    const ft = buildExpectationJson(baseMatcher(), action)['httpForwardTemplate'] as Record<string, unknown>;
    expect(ft['templateFile']).toBe('templates/f.mustache');
    expect(ft).not.toHaveProperty('template');
  });
});

describe('buildExpectationJson static response with templated FILE body', () => {
  it('emits a FILE body with templateType and contentType on the body (not as a header)', () => {
    const action: StandardActionPayload = {
      type: 'static',
      static: {
        statusCode: 200,
        body: '',
        contentType: 'application/json',
        bodyFromFile: true,
        filePath: 'responses/order.json',
        fileTemplateType: 'MUSTACHE',
      },
    };
    const r = buildExpectationJson(baseMatcher(), action)['httpResponse'] as Record<string, unknown>;
    const body = r['body'] as Record<string, unknown>;
    expect(body['type']).toBe('FILE');
    expect(body['filePath']).toBe('responses/order.json');
    expect(body['templateType']).toBe('MUSTACHE');
    expect(body['contentType']).toBe('application/json');
    // content type lives on the FILE body, so it must not also be emitted as a header
    expect(r).not.toHaveProperty('headers');
  });

  it('emits a plain FILE body (no templateType) when none is selected', () => {
    const action: StandardActionPayload = {
      type: 'static',
      static: {
        statusCode: 200,
        body: '',
        contentType: '',
        bodyFromFile: true,
        filePath: 'responses/static.json',
        fileTemplateType: '',
      },
    };
    const body = (buildExpectationJson(baseMatcher(), action)['httpResponse'] as Record<string, unknown>)['body'] as Record<string, unknown>;
    expect(body['type']).toBe('FILE');
    expect(body['filePath']).toBe('responses/static.json');
    expect(body).not.toHaveProperty('templateType');
  });

  it('falls back to the inline string body when bodyFromFile is false', () => {
    const action: StandardActionPayload = {
      type: 'static',
      static: {
        statusCode: 200,
        body: '{"ok":true}',
        contentType: 'application/json',
        bodyFromFile: false,
        filePath: '',
        fileTemplateType: '',
      },
    };
    const r = buildExpectationJson(baseMatcher(), action)['httpResponse'] as Record<string, unknown>;
    expect(r['body']).toBe('{"ok":true}');
  });
});
