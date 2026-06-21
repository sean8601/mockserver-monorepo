# frozen_string_literal: true

RSpec.describe MockServer::Client do
  let(:host) { 'localhost' }
  let(:port) { 1080 }
  let(:base_url) { "http://#{host}:#{port}" }
  let(:client) { MockServer::Client.new(host, port) }

  after { client.close }

  def sample_scenario
    MockServer::LoadScenario.new(
      name: 'checkout-spike',
      template_type: 'VELOCITY',
      labels: { 'team' => 'payments' },
      max_requests: 1000,
      profile: MockServer::LoadProfile.new(
        type: 'LINEAR',
        start_vus: 1,
        end_vus: 50,
        duration_millis: 60_000,
        iteration_pacing_millis: 200
      ),
      steps: [
        MockServer::LoadStep.new(
          name: 'place-order',
          labels: { 'critical' => 'true' },
          think_time: MockServer::Delay.new(time_unit: 'MILLISECONDS', value: 500),
          request: MockServer::HttpRequest.new(
            method: 'POST',
            path: '/orders',
            socket_address: MockServer::SocketAddress.new(host: 'shop.svc', port: 8080, scheme: 'HTTP')
          )
        )
      ]
    )
  end

  # -------------------------------------------------------------------
  # load_scenario (PUT)
  # -------------------------------------------------------------------
  describe '#load_scenario' do
    it 'sends PUT to /mockserver/loadScenario with the scenario body' do
      stub_request(:put, "#{base_url}/mockserver/loadScenario")
        .to_return(status: 200, body: JSON.generate({ 'status' => 'RUNNING', 'name' => 'checkout-spike' }))

      result = client.load_scenario(sample_scenario)

      expect(WebMock).to have_requested(:put, "#{base_url}/mockserver/loadScenario")
        .with { |r|
          parsed = JSON.parse(r.body)
          parsed['name'] == 'checkout-spike' &&
            parsed['templateType'] == 'VELOCITY' &&
            parsed['maxRequests'] == 1000 &&
            parsed['profile']['type'] == 'LINEAR' &&
            parsed['profile']['startVus'] == 1 &&
            parsed['profile']['endVus'] == 50 &&
            parsed['profile']['durationMillis'] == 60_000 &&
            parsed['profile']['iterationPacingMillis'] == 200 &&
            parsed['steps'][0]['name'] == 'place-order' &&
            parsed['steps'][0]['thinkTime']['value'] == 500 &&
            parsed['steps'][0]['request']['method'] == 'POST' &&
            parsed['steps'][0]['request']['path'] == '/orders' &&
            parsed['steps'][0]['request']['socketAddress']['host'] == 'shop.svc' &&
            parsed['steps'][0]['request']['socketAddress']['port'] == 8080
        }
      expect(result['status']).to eq('RUNNING')
    end

    it 'accepts a plain hash scenario' do
      stub_request(:put, "#{base_url}/mockserver/loadScenario")
        .to_return(status: 200, body: JSON.generate({ 'status' => 'RUNNING' }))

      client.load_scenario({ 'name' => 'raw', 'profile' => { 'type' => 'CONSTANT', 'vus' => 5 } })

      expect(WebMock).to have_requested(:put, "#{base_url}/mockserver/loadScenario")
        .with { |r| JSON.parse(r.body)['profile']['vus'] == 5 }
    end

    it 'raises a clear error on 403 (load generation disabled)' do
      stub_request(:put, "#{base_url}/mockserver/loadScenario")
        .to_return(status: 403, body: '')

      expect { client.load_scenario(sample_scenario) }
        .to raise_error(MockServer::Error, /load generation is disabled/)
    end

    it 'raises Error on other failures' do
      stub_request(:put, "#{base_url}/mockserver/loadScenario")
        .to_return(status: 400, body: '{"error":"bad"}')

      expect { client.load_scenario(sample_scenario) }
        .to raise_error(MockServer::Error, /Failed to start load scenario/)
    end
  end

  # -------------------------------------------------------------------
  # load_scenario_status (GET)
  # -------------------------------------------------------------------
  describe '#load_scenario_status' do
    it 'sends GET to /mockserver/loadScenario' do
      stub_request(:get, "#{base_url}/mockserver/loadScenario")
        .to_return(status: 200, body: JSON.generate({ 'status' => 'RUNNING', 'completedRequests' => 42 }))

      result = client.load_scenario_status

      expect(WebMock).to have_requested(:get, "#{base_url}/mockserver/loadScenario")
      expect(result['completedRequests']).to eq(42)
    end

    it 'raises a clear error on 403 (load generation disabled)' do
      stub_request(:get, "#{base_url}/mockserver/loadScenario")
        .to_return(status: 403, body: '')

      expect { client.load_scenario_status }
        .to raise_error(MockServer::Error, /load generation is disabled/)
    end

    it 'raises Error on other failures' do
      stub_request(:get, "#{base_url}/mockserver/loadScenario")
        .to_return(status: 500, body: '{"error":"boom"}')

      expect { client.load_scenario_status }
        .to raise_error(MockServer::Error, /Failed to get load scenario status/)
    end
  end

  # -------------------------------------------------------------------
  # stop_load_scenario (DELETE)
  # -------------------------------------------------------------------
  describe '#stop_load_scenario' do
    it 'sends DELETE to /mockserver/loadScenario' do
      stub_request(:delete, "#{base_url}/mockserver/loadScenario")
        .to_return(status: 200, body: JSON.generate({ 'status' => 'STOPPED' }))

      result = client.stop_load_scenario

      expect(WebMock).to have_requested(:delete, "#{base_url}/mockserver/loadScenario")
      expect(result['status']).to eq('STOPPED')
    end

    it 'raises a clear error on 403 (load generation disabled)' do
      stub_request(:delete, "#{base_url}/mockserver/loadScenario")
        .to_return(status: 403, body: '')

      expect { client.stop_load_scenario }
        .to raise_error(MockServer::Error, /load generation is disabled/)
    end
  end

  # -------------------------------------------------------------------
  # model serialisation
  # -------------------------------------------------------------------
  describe 'LoadScenario models' do
    it 'serialises to the LoadScenario JSON contract' do
      hash = sample_scenario.to_h
      expect(hash['name']).to eq('checkout-spike')
      expect(hash['profile']['type']).to eq('LINEAR')
      expect(hash['steps'][0]['request']['path']).to eq('/orders')
      expect(hash['steps'][0]['thinkTime']).to eq({ 'timeUnit' => 'MILLISECONDS', 'value' => 500 })
    end

    it 'round-trips through from_hash' do
      original = sample_scenario
      roundtrip = MockServer::LoadScenario.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end

    it 'omits nil fields' do
      scenario = MockServer::LoadScenario.new(
        name: 'minimal',
        profile: MockServer::LoadProfile.new(type: 'CONSTANT', vus: 3, duration_millis: 1000),
        steps: [MockServer::LoadStep.new(request: MockServer::HttpRequest.new(method: 'GET', path: '/'))]
      )
      hash = scenario.to_h
      expect(hash).not_to have_key('templateType')
      expect(hash).not_to have_key('labels')
      expect(hash).not_to have_key('maxRequests')
      expect(hash['profile']).not_to have_key('startVus')
      expect(hash['steps'][0]).not_to have_key('thinkTime')
    end
  end
end
