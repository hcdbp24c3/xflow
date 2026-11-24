import 'package:flutter/material.dart';
import '../../../core/theme/app_theme.dart';

class TextTweetCard extends StatelessWidget {
  final String text;
  const TextTweetCard({super.key, required this.text});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      height: double.infinity,
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [AppColors.surfaceHigh, AppColors.background],
        ),
      ),
      padding: const EdgeInsets.fromLTRB(32, 96, 72, 200),
      alignment: Alignment.center,
      child: SingleChildScrollView(
        child: Text(
          text,
          textAlign: TextAlign.center,
          style: Theme.of(context).textTheme.titleLarge?.copyWith(
                color: AppColors.textPrimary,
                fontWeight: FontWeight.w600,
                height: 1.35,
              ),
        ),
      ),
    );
  }
}
