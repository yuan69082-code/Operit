module.exports = {
  source: 'shared', theme_mode: 'light', use_system_theme: false, use_custom_colors: false,
  palette: { background_color: '#faf8f5', surface_color: '#fffdf9', surface_variant_color: '#f4edf0', surface_container_color: '#f7f0f2', surface_container_high_color: '#efe4e8', primary_color: '#9d647a', secondary_color: '#927786', primary_container_color: '#f4dbe5', on_primary_container_color: '#4c2636', on_surface_color: '#302b2d', on_surface_variant_color: '#756c70', outline_color: '#93868c', outline_variant_color: '#d8cbd1' },
  background: { type: 'color', opacity: 0 }, header: { transparent: false, overlay: false },
  input: { style: 'classic', transparent: false, floating: false, liquid_glass: false, water_glass: false },
  font: { type: 'system', scale: 1 }, chat_style: 'bubble', show_thinking_process: true, show_status_tags: true, show_input_processing_status: true,
  display: { show_user_name: false, show_role_name: true, show_model_name: false, show_model_provider: false, show_message_token_stats: false, show_message_timing_stats: false, show_message_timestamp: true, tool_collapse_mode: 'COLLAPSED', global_user_name: '' },
  bubble: { show_avatar: false, wide_layout: true, cursor_user_follow_theme: true, cursor_user_liquid_glass: false, cursor_user_water_glass: false, user_liquid_glass: false, user_water_glass: false, assistant_liquid_glass: false, assistant_water_glass: false, user_rounded: true, assistant_rounded: true, user_padding_left: 16, user_padding_right: 16, assistant_padding_left: 16, assistant_padding_right: 16, user_image: { enabled: false }, assistant_image: { enabled: false } },
  avatars: { shape: 'circle' }
};
