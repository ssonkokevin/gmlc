import type {
  AlertPage,
  Deferred,
  Mix,
  OpsSessionView,
  PeerPage,
  RequestPage,
  Summary,
  Throughput,
} from './types';

// Console-only shapes: an operator session is the API's session view, and the
// dashboard is one screen's bundle of seven independent responses.
export type Session = OpsSessionView;

export interface DashboardData {
  summary: Summary;
  throughput: Throughput;
  mix: Mix;
  peers: PeerPage;
  alerts: AlertPage;
  requests: RequestPage;
  deferred: Deferred;
}
