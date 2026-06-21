<?php

declare(strict_types=1);

namespace MockServer;

/**
 * The ramp profile describing the target concurrency of a {@see LoadScenario}
 * over time.
 *
 * Use {@see LoadProfile::constant()} to hold a fixed number of virtual users for
 * the whole duration, or {@see LoadProfile::linear()} to ramp linearly from
 * {@code startVus} to {@code endVus}.
 */
class LoadProfile implements \JsonSerializable
{
    private string $type;
    private int $durationMillis;
    private ?int $vus = null;
    private ?int $startVus = null;
    private ?int $endVus = null;
    private ?int $iterationPacingMillis = null;

    private function __construct(string $type, int $durationMillis)
    {
        $this->type = $type;
        $this->durationMillis = $durationMillis;
    }

    /**
     * Hold {@code $vus} virtual users for the whole {@code $durationMillis}.
     */
    public static function constant(int $vus, int $durationMillis): self
    {
        $profile = new self('CONSTANT', $durationMillis);
        $profile->vus = $vus;

        return $profile;
    }

    /**
     * Ramp linearly from {@code $startVus} to {@code $endVus} over
     * {@code $durationMillis}.
     */
    public static function linear(int $startVus, int $endVus, int $durationMillis): self
    {
        $profile = new self('LINEAR', $durationMillis);
        $profile->startVus = $startVus;
        $profile->endVus = $endVus;

        return $profile;
    }

    /**
     * Set an optional minimum delay between successive iterations of a virtual user.
     */
    public function iterationPacingMillis(int $iterationPacingMillis): self
    {
        $this->iterationPacingMillis = $iterationPacingMillis;

        return $this;
    }

    /**
     * @return array<string, mixed>
     */
    public function jsonSerialize(): array
    {
        return $this->toArray();
    }

    /**
     * @return array<string, mixed>
     */
    public function toArray(): array
    {
        $result = [
            'type' => $this->type,
            'durationMillis' => $this->durationMillis,
        ];
        if ($this->vus !== null) {
            $result['vus'] = $this->vus;
        }
        if ($this->startVus !== null) {
            $result['startVus'] = $this->startVus;
        }
        if ($this->endVus !== null) {
            $result['endVus'] = $this->endVus;
        }
        if ($this->iterationPacingMillis !== null) {
            $result['iterationPacingMillis'] = $this->iterationPacingMillis;
        }

        return $result;
    }
}
