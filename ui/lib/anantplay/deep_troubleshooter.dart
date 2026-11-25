// AnantPlay Sinhala Player v3.4.6.e — THE 6-DOMAIN DEEP TROUBLESHOOTER MODEL.
//
// One deep probe outcome from the native DeepProbe engine (54 live checks
// across every feature in the entire app), plus the domain grouping and the
// verdict helpers the Help tab's collapsible domain cards render. The
// bridge methods (runDeepProbe / lastDeepResults / deepReport) live in
// bridge.dart's AnantPlay class on the same anantplay/troubleshoot channel
// as the 10-point runProbe.

import 'dart:convert';

/// One probe outcome (DeepProbe.Outcome, JSON-decoded).
class DeepOutcome {
  const DeepOutcome({
    required this.id,
    required this.domain,
    required this.name,
    required this.pass,
    required this.ms,
    required this.detail,
  });

  factory DeepOutcome.fromMap(Map<dynamic, dynamic> map) => DeepOutcome(
        id: (map['id'] as num?)?.toInt() ?? 0,
        domain: (map['domain'] as String?) ?? '',
        name: (map['name'] as String?) ?? '',
        pass: map['pass'] == true,
        ms: (map['ms'] as num?)?.toDouble() ?? 0,
        detail: (map['detail'] as String?) ?? '',
      );

  final int id;
  final String domain; // A|B|C|D|E|F
  final String name;
  final bool pass;
  final double ms;
  final String detail;
}

/// The six domains, their titles, and the grouping helpers.
class DeepDomain {
  const DeepDomain(this.code, this.title);

  final String code;
  final String title;

  static const all = [
    DeepDomain('A', 'Auto-Updater Law'),
    DeepDomain('B', 'Home & Cast Receiver'),
    DeepDomain('C', 'Settings & Customizations'),
    DeepDomain('D', 'Browser'),
    DeepDomain('E', 'Cinema Player & Subtitles'),
    DeepDomain('F', 'Permissions & Privacy'),
  ];

  static String titleOf(String code) {
    for (final d in all) {
      if (d.code == code) return d.title;
    }
    return code;
  }
}

/// Decodes the outcomes the anantplay/troubleshoot events stream pushes when
/// a deep run ends (a List of maps, or a JSON string on some hosts).
List<DeepOutcome> decodeDeepOutcomes(Object? raw) {
  if (raw is String && raw.startsWith('[')) {
    try {
      final list = jsonDecode(raw) as List<dynamic>;
      return list
          .whereType<Map>()
          .map(DeepOutcome.fromMap)
          .toList(growable: false);
    } catch (_) {
      return const [];
    }
  }
  if (raw is! List) return const [];
  return raw.whereType<Map>().map(DeepOutcome.fromMap).toList(growable: false);
}
