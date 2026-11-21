import type { ReactNode } from 'react';
import { axisLabel, fmtInt } from './format';
import type { NameCount, ProtocolPoint, RangeKey } from './types';

export function Panel(props: {
  id?: string;
  title: string;
  subtitle?: string;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={`panel ${props.className ?? ''}`} id={props.id}>
      <header className="panel-head">
        <div>
          <h2>{props.title}</h2>
          {props.subtitle ? <p className="panel-sub">{props.subtitle}</p> : null}
        </div>
        {props.actions ? <div className="panel-actions">{props.actions}</div> : null}
      </header>
      {props.children}
    </section>
  );
}

export type Tone = 'good' | 'bad' | 'warn' | 'info' | 'neutral';

export function StatusChip({ label, tone = 'neutral' }: { label: string; tone?: Tone }) {
  return <span className={`chip chip-${tone}`}>{label}</span>;
}

export function stateTone(state: string | undefined): Tone {
  switch ((state ?? '').toUpperCase()) {
    case 'ANSWERED':
    case 'DELIVERED':
    case 'UP':
    case 'ALLOWED':
      return 'good';
    case 'FAILED':
    case 'TIMEOUT':
    case 'ABORTED':
    case 'DOWN':
    case 'CRITICAL':
      return 'bad';
    case 'REQUESTED':
    case 'RECEIVED':
    case 'ROUTED':
    case 'IDLE':
    case 'WARNING':
      return 'warn';
    case 'NOT_CONFIGURED':
      return 'info';
    default:
      return 'neutral';
  }
}

export function Sparkline({ values, label }: { values: Array<number | null | undefined>; label: string }) {
  const width = 132;
  const height = 34;
  const pad = 3;
  const finite = values.filter((value): value is number => typeof value === 'number' && Number.isFinite(value));
  if (finite.length < 2) {
    return (
      <svg className="spark" viewBox={`0 0 ${width} ${height}`} role="img" aria-label={`${label}: not enough points`}>
        <line className="spark-line-empty" x1={pad} y1={height - pad} x2={width - pad} y2={height - pad} />
      </svg>
    );
  }
  const min = Math.min(...finite);
  const max = Math.max(...finite);
  const span = max - min || 1;
  const step = (width - pad * 2) / (finite.length - 1);
  const points = finite
    .map((value, index) => {
      const x = pad + index * step;
      const y = height - pad - ((value - min) / span) * (height - pad * 2);
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(' ');
  return (
    <svg className="spark" viewBox={`0 0 ${width} ${height}`} role="img"
      aria-label={`${label}, low ${fmtInt(min)}, high ${fmtInt(max)}`}>
      <polyline className="spark-line" points={points} />
    </svg>
  );
}

export function KpiCard(props: {
  label: string;
  value: string;
  hint?: string;
  delta?: { text: string; tone: Tone };
  spark?: { values: Array<number | null | undefined>; label: string };
  unavailable?: string;
}) {
  return (
    <article className="kpi">
      <p className="kpi-label">{props.label}</p>
      <p className="kpi-value">{props.value}</p>
      {props.unavailable ? <p className="kpi-unavailable">{props.unavailable}</p> : null}
      <div className="kpi-foot">
        {props.delta ? <span className={`delta delta-${props.delta.tone}`}>{props.delta.text}</span> : null}
        {props.hint ? <span className="kpi-hint">{props.hint}</span> : null}
      </div>
      {props.spark ? <Sparkline values={props.spark.values} label={props.spark.label} /> : null}
    </article>
  );
}

export function ThroughputChart({ points, range }: { points: ProtocolPoint[]; range: RangeKey }) {
  if (points.length === 0) {
    return <p className="empty">No requests were recorded in this window.</p>;
  }
  const width = 640;
  const height = 200;
  const padLeft = 46;
  const padRight = 8;
  const padTop = 10;
  const padBottom = 26;
  const plotW = width - padLeft - padRight;
  const plotH = height - padTop - padBottom;
  const max = Math.max(1, ...points.map((p) => p.mapRequests + p.diameterRequests + p.unattributed));
  const slot = plotW / points.length;
  const barW = Math.max(2, Math.min(26, slot * 0.62));
  const stride = Math.max(1, Math.ceil(points.length / 8));

  const yOf = (value: number) => padTop + plotH - (value / max) * plotH;

  return (
    <>
      <svg className="chart" viewBox={`0 0 ${width} ${height}`} role="img"
        aria-label={`Throughput per bucket: ${points.length} buckets, busiest ${fmtInt(max)} requests`}>
        {[0, 0.5, 1].map((fraction) => (
          <g key={fraction}>
            <line className="gridline" x1={padLeft} x2={width - padRight}
              y1={yOf(max * fraction)} y2={yOf(max * fraction)} />
            <text className="axis-text" x={padLeft - 6} y={yOf(max * fraction) + 4} textAnchor="end">
              {fmtInt(Math.round(max * fraction))}
            </text>
          </g>
        ))}
        {points.map((point, index) => {
          const x = padLeft + index * slot + (slot - barW) / 2;
          const mapY = yOf(point.mapRequests);
          const diameterY = yOf(point.mapRequests + point.diameterRequests);
          const unattributedY = yOf(point.mapRequests + point.diameterRequests + point.unattributed);
          return (
            <g key={point.bucket}>
              <title>{`${axisLabel(point.bucket, range)}: MAP ${point.mapRequests}, DIAMETER ${point.diameterRequests}, unattributed ${point.unattributed}`}</title>
              <rect className="bar bar-map" x={x} y={mapY} width={barW}
                height={Math.max(0, padTop + plotH - mapY)} rx={1.5} />
              <rect className="bar bar-diameter" x={x} y={diameterY} width={barW}
                height={Math.max(0, mapY - diameterY)} rx={1.5} />
              <rect className="bar bar-unattributed" x={x} y={unattributedY} width={barW}
                height={Math.max(0, diameterY - unattributedY)} rx={1.5} />
              {index % stride === 0 ? (
                <text className="axis-text" x={x + barW / 2} y={height - 8} textAnchor="middle">
                  {axisLabel(point.bucket, range)}
                </text>
              ) : null}
            </g>
          );
        })}
      </svg>
      <div className="legend">
        <span className="legend-item"><i className="swatch swatch-map" />MAP</span>
        <span className="legend-item"><i className="swatch swatch-diameter" />Diameter</span>
        <span className="legend-item"><i className="swatch swatch-unattributed" />Unattributed</span>
      </div>
      <details className="data-table">
        <summary>View data</summary>
        <table>
          <caption>Requests per bucket</caption>
          <thead>
            <tr><th scope="col">Bucket</th><th scope="col">MAP</th><th scope="col">Diameter</th>
              <th scope="col">Unattributed</th><th scope="col">Total</th></tr>
          </thead>
          <tbody>
            {points.map((point) => (
              <tr key={point.bucket}>
                <th scope="row">{axisLabel(point.bucket, range)}</th>
                <td>{fmtInt(point.mapRequests)}</td>
                <td>{fmtInt(point.diameterRequests)}</td>
                <td>{fmtInt(point.unattributed)}</td>
                <td>{fmtInt(point.mapRequests + point.diameterRequests + point.unattributed)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </>
  );
}

export function Donut({ rows, title }: { rows: NameCount[]; title: string }) {
  const total = rows.reduce((sum, row) => sum + row.count, 0);
  if (total === 0) {
    return <p className="empty">Nothing recorded in this window.</p>;
  }
  const sorted = [...rows].sort((a, b) => b.count - a.count);
  const head = sorted.slice(0, 6);
  const tail = sorted.slice(6);
  const slices: NameCount[] = tail.length
    ? [...head, { name: 'OTHER', count: tail.reduce((sum, row) => sum + row.count, 0) }]
    : head;
  const radius = 54;
  const circumference = 2 * Math.PI * radius;
  let offset = 0;
  return (
    <div className="donut-wrap">
      <svg className="donut" viewBox="0 0 140 140" role="img"
        aria-label={`${title}: ${slices.map((slice) => `${slice.name} ${slice.count}`).join(', ')}`}>
        <circle className="donut-track" cx={70} cy={70} r={radius} />
        {slices.map((slice, index) => {
          const length = (slice.count / total) * circumference;
          const circle = (
            <circle key={slice.name} className={`slice slice-${index % 8}`} cx={70} cy={70} r={radius}
              strokeDasharray={`${length} ${circumference - length}`} strokeDashoffset={-offset}>
              <title>{`${slice.name}: ${slice.count} (${Math.round((slice.count / total) * 100)}%)`}</title>
            </circle>
          );
          offset += length;
          return circle;
        })}
        <text className="donut-total" x={70} y={66} textAnchor="middle">{fmtInt(total)}</text>
        <text className="donut-caption" x={70} y={84} textAnchor="middle">records</text>
      </svg>
      <ul className="donut-legend">
        {slices.map((slice, index) => (
          <li key={slice.name}>
            <i className={`swatch slice-${index % 8}`} />
            <span className="donut-name">{slice.name}</span>
            <span className="donut-value">{fmtInt(slice.count)}</span>
            <span className="donut-share">{Math.round((slice.count / total) * 100)}%</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

export function MethodSplit({ byMethod }: { byMethod: NameCount[] }) {
  const total = byMethod.reduce((sum, row) => sum + row.count, 0);
  if (total === 0) {
    return <p className="empty">No completed record names a method.</p>;
  }
  return (
    <ul className="bars">
      {[...byMethod].sort((a, b) => b.count - a.count).map((row) => {
        const share = (row.count / total) * 100;
        return (
          <li key={row.name}>
            <span className="bars-name">{row.name}</span>
            <svg className="bars-svg" viewBox="0 0 100 8" preserveAspectRatio="none" aria-hidden="true">
              <rect className={row.name === 'CELL_ID' ? 'bars-fill-method' : 'bars-fill-other'}
                x={0} y={0} width={share} height={8} rx={2} />
            </svg>
            <span className="bars-value">{fmtInt(row.count)}</span>
          </li>
        );
      })}
    </ul>
  );
}
