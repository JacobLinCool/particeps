/**
 * The only study-definition fields Particeps may project into participant-generated UI.
 *
 * Keep this DTO deliberately boring. Researcher-authored consent, notifications, and surveys are
 * rendered through their existing signed-content paths; treatment details never cross this
 * boundary accidentally because this projection has nowhere to put them.
 */
import { trafficShapingEnabled, type CollectorId, type StudyConfiguration } from './types';

export const PARTICIPANT_VPN_DISCLOSURE = {
  en: 'This study may use a VPN on this device to adjust how quickly some apps transfer data. Traffic stays on your usual network and is not sent through a Particeps server. Particeps only checks whether the study’s apps are installed; it does not save or upload your installed-app list. On Android 17 or later, local-network access is used only to forward local connections those apps initiate; Particeps does not discover local devices. Another VPN can interrupt this function; if that happens, the study pauses.',
  'zh-TW': '這項研究可能會使用此裝置上的 VPN，調整部分 App 傳輸資料的速度。流量仍使用你原本的網路，不會經由 Particeps 伺服器傳送。Particeps 只會檢查研究所需 App 是否已安裝，不會儲存或上傳已安裝 App 清單。Android 17 以上的本機網路權限只用來轉送這些 App 原本發出的本機連線；Particeps 不會搜尋本機網路裝置。其他 VPN 可能中斷此功能，屆時研究會暫停。'
} as const;

export interface ParticipantStudyUiModel {
  title: string;
  purpose: string;
  data_category_ids: CollectorId[];
  shows_traffic_disclosure: boolean;
}

export function participantStudyUiModel(configuration: StudyConfiguration): ParticipantStudyUiModel {
  return {
    title: configuration.title,
    purpose: configuration.purpose,
    data_category_ids: configuration.collectors.map((collector) => collector.id),
    shows_traffic_disclosure: trafficShapingEnabled(configuration.traffic_shaping)
  };
}
