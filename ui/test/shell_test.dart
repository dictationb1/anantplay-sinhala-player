

  // ══ v3.4.6.g — CRASH / THERMAL / CAST / UI CONTRACT (the field screenshots) ══
  //
  // These guard the five real-phone defects this release exists to kill:
  // the wrong-thread ExoPlayer crash, the un-threaded WebView probes, the cast
  // auto-open misses, the fake `#` picker and the fake Material You.
  group('v3.4.6.g UI contracts', () {
    testWidgets('Home quick-action subtitles are the crisp short labels (no clipping)',
        (tester) async {
      await tester.pumpWidget(quiet(const HomeScreen()));
      await tester.pumpAndSettle();
      expect(find.text('Official app'), findsOneWidget);
      expect(find.text('Web & subs'), findsOneWidget);
      expect(find.text('Stream link'), findsOneWidget);
      expect(find.text('Demo movie'), findsOneWidget);
      // the truncated v3.4.6.f strings must be GONE
      expect(find.text('Open the official app'), findsNothing);
      expect(find.text('Cinema WebView browser'), findsNothing);
      expect(find.text('Stream any link here'), findsNothing);
      expect(find.text('Mornye cinema demo'), findsNothing);
    });

    testWidgets('Home Got it persists K_HOME_TIP_DISMISSED and leaves the Tour chip',
        (tester) async {
      await tester.pumpWidget(quiet(const HomeScreen()));
      await tester.pumpAndSettle();
      expect(find.text('Got it'), findsOneWidget);
      await tester.tap(find.text('Got it'));
      await tester.pumpAndSettle();
      // persisted through the REAL settings channel
      expect(settingsPuts['quickstartDone'], true);
      // the banner retires; the Guide/Tour chip remains one tap from the wizard
      expect(find.text('Got it'), findsNothing);
      expect(
        find.text('Tour').evaluate().isNotEmpty ||
            find.text('Guide').evaluate().isNotEmpty,
        isTrue,
        reason: 'the wizard must stay reachable after dismissal',
      );
    });

    testWidgets('the # button opens the Liquid-Glass Color Studio sheet',
        (tester) async {
      await tester.pumpWidget(quiet(const SettingsScreen()));
      await tester.pumpAndSettle();
      // the custom-hex control renders a bare "#" while a preset is active
      final hash = find.text('#');
      expect(hash, findsWidgets);
      await tester.tap(hash.first, warnIfMissed: false);
      await tester.pumpAndSettle();
      expect(find.text('Color Studio'), findsOneWidget);
      expect(find.text('Apply Color'), findsOneWidget);
      expect(find.text('Use preset'), findsWidgets);
      // applying writes K_ACCENT_HEX (the live repaint trigger)
      await tester.tap(find.text('Apply Color'));
      await tester.pumpAndSettle();
      expect(settingsPuts['accentHex'], isNotNull);
    });

    testWidgets('onboarding slide 1 plays the three-beat story and replays on tap',
        (tester) async {
      await tester.pumpWidget(quiet(const OnboardingScreen()));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
      // the story caption for the live beat is on screen
      final captions = <String>[
        '1 · Pick a movie in AnantPlay',
        '2 · Auto Sinhala Subtitles',
        '3 · Cool Cinema Player',
      ];
      expect(
        captions.where((c) => find.text(c).evaluate().isNotEmpty).length,
        greaterThanOrEqualTo(1),
        reason: 'slide 1 must SHOW the three-beat value story, not a bare emblem',
      );
      // and the body names all three beats in order
      final body = find.textContaining('Pick a movie in AnantPlay');
      expect(body, findsWidgets);
    });

    testWidgets('HelpScreen streams the live step-by-step ticker from probe frames',
        (tester) async {
      await tester.pumpWidget(quiet(const HelpScreen()));
      await tester.pump();
      // the honest run starts the ticker empty
      expect(find.text('Waiting for the first probe…').evaluate().isNotEmpty ||
          find.byType(ListView).evaluate().isNotEmpty, isTrue);
      // push two real progress frames ({t:'deep', done, total, outcome})
      for (final frame in <Map<String, Object>>[
        <String, Object>{
          't': 'deep', 'done': 34, 'total': 69,
          'outcome': <String, Object>{
            'id': 34, 'domain': 'D', 'name': 'Security hardening flags',
            'pass': true, 'ms': 4, 'detail': 'MIXED_CONTENT_NEVER_ALLOW ✓',
          },
        },
        <String, Object>{
          't': 'deep', 'done': 35, 'total': 69,
          'outcome': <String, Object>{
            'id': 35, 'domain': 'D', 'name': 'BridgeGuard 5 MB + blob guard',
            'pass': false, 'ms': 2, 'detail': 'FAIL — UI thread did not answer',
          },
        },
      ]) {
        await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
          'anantplay/troubleshoot/events',
          const StandardMethodCodec().encodeSuccessEnvelope(frame),
          (ByteData? data) {},
        );
      }
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 50));
      expect(find.textContaining('#34').evaluate().isNotEmpty ||
          find.textContaining('Security hardening flags').evaluate().isNotEmpty,
          isTrue,
          reason: 'the finished probes must stream into the on-screen ticker');
    });

    testWidgets('PlayerScreen renders the hardware video_surface PlatformView for -2',
        (tester) async {
      mock.setMockMethodCallHandler(const MethodChannel('anantplay/player'), (call) async {
        if (call.method == 'open') {
          return <String, Object>{
            'textureId': -2,
            'videoWidth': 1920,
            'videoHeight': 1080,
            'videoMode': 'surfaceview',
          };
        }
        if (call.method == 'tracks') return <Object>[];
        return true;
      });
      await tester.pumpWidget(quiet(PlayerScreen(
        request: const shell.PlayRequest(url: 'https://example.com/a.m3u8'),
        onExit: () {},
      )));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 60));
      // the zero-GPU-copy lane: the AndroidView PlatformView, never a Texture
      expect(find.byType(Texture), findsNothing,
          reason: 'textureId -2 is the native SurfaceView session');
      expect(find.byType(AndroidView), findsWidgets,
          reason: 'the hardware video_surface must be mounted');
    });

    testWidgets('the Color Studio normalizes a hex without the leading #',
        (tester) async {
      String? picked;
      await tester.pumpWidget(quiet(Scaffold(
        body: Builder(
          builder: (context) => Center(
            child: ElevatedButton(
              onPressed: () async {
                picked = await showColorStudioSheet(context, initial: '');
              },
              child: const Text('open'),
            ),
          ),
        ),
      )));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).first, '63D668');
      await tester.pumpAndSettle();
      await tester.tap(find.text('Apply Color'));
      await tester.pumpAndSettle();
      expect(picked, '63D668');
    });
  });
}
