#!/usr/bin/env ruby
# 仅重新生成工程时需要 xcodeproj；日常构建直接使用已提交的工程。
require 'xcodeproj'
require 'pathname'

root = File.expand_path('..', __dir__)
project = Xcodeproj::Project.new(File.join(root, 'QSMH5App.xcodeproj'))
project.build_configurations.delete_if { |config| config.name == 'Debug' }
project.add_build_configuration('Debug-Local', :debug)
project.add_build_configuration('Debug-Integration', :debug)
project.root_object.compatibility_version = 'Xcode 14.0'
project.root_object.development_region = 'zh-Hans'
project.root_object.known_regions = ['zh-Hans', 'en', 'Base']
project.root_object.attributes['LastUpgradeCheck'] = '1640'

app = project.new_target(:application, 'QSMH5App', :ios, '14.0')
unit = project.new_target(:unit_test_bundle, 'QSMH5AppTests', :ios, '14.0')
ui = project.new_target(:ui_test_bundle, 'QSMH5AppUITests', :ios, '14.0')
configs = project.main_group.new_group('Config', 'Config')
references = {}
Dir[File.join(root, 'Config', '*.xcconfig')].sort.each do |path|
  next if File.basename(path).include?('.local.') || File.basename(path) == 'LocalOverrides.xcconfig'
  references[File.basename(path)] = configs.new_file(File.basename(path))
end
['App', 'Core', 'Features', 'Tests', 'UITests'].each do |folder|
  group = project.main_group.new_group(folder, folder)
  target = folder == 'Tests' ? unit : folder == 'UITests' ? ui : app
  Dir[File.join(root, folder, '*.swift')].sort.each { |path| target.source_build_phase.add_file_reference(group.new_file(File.basename(path))) }
end
resources = project.main_group.new_group('Resources', 'Resources')
Dir[File.join(root, 'Resources', '*')].sort.each do |path|
  ref = resources.new_file(File.basename(path))
  ref.last_known_file_type = "folder" if File.directory?(path) && !path.end_with?(".xcassets")
  app.resources_build_phase.add_file_reference(ref)
end
[app, unit, ui].each do |target|
  ['Debug-Local', 'Debug-Integration', 'Release'].each { |name| target.add_build_configuration(name, name == 'Release' ? :release : :debug) }
  target.build_configurations.delete_if { |config| config.name == 'Debug' }
  target.build_configurations.each do |config|
    settings = config.build_settings
    settings['SWIFT_VERSION'] = '5.0'
    settings['IPHONEOS_DEPLOYMENT_TARGET'] = '14.0'
    settings['TARGETED_DEVICE_FAMILY'] = '1'
    settings['CODE_SIGN_STYLE'] = target == app ? nil : 'Automatic'
    settings['ENABLE_USER_SCRIPT_SANDBOXING'] = 'NO'
    settings['GENERATE_INFOPLIST_FILE'] = target == app ? 'NO' : 'YES'
    settings['SWIFT_OPTIMIZATION_LEVEL'] = config.name == 'Release' ? '-O' : '-Onone'
    settings['ENABLE_TESTABILITY'] = config.name == 'Release' ? 'NO' : 'YES'
    settings['DEBUG_INFORMATION_FORMAT'] = config.name == 'Release' ? 'dwarf-with-dsym' : 'dwarf'
    if target == app
      config.base_configuration_reference = references[config.name + '.xcconfig']
      settings['PRODUCT_NAME'] = 'QSMH5App'
      settings['DEFINES_MODULE'] = 'YES'
      settings.delete('PRODUCT_BUNDLE_IDENTIFIER')
      settings.delete('INFOPLIST_FILE')
    else
      settings['PRODUCT_BUNDLE_IDENTIFIER'] = 'com.un74meng.yjhcmc.' + (target == unit ? 'tests' : 'uitests')
      settings['SWIFT_ACTIVE_COMPILATION_CONDITIONS'] = '$(inherited) DEBUG'
      settings['TEST_TARGET_NAME'] = 'QSMH5App'
      if target == unit
        settings['TEST_HOST'] = '$(BUILT_PRODUCTS_DIR)/QSMH5App.app/QSMH5App'
        settings['BUNDLE_LOADER'] = '$(TEST_HOST)'
      end
    end
  end
end
unit.add_dependency(app)
ui.add_dependency(app)
phase = app.new_shell_script_build_phase('复制阿里云 SDK 资源')
phase.shell_script = '"$PROJECT_DIR/Scripts/copy_aliyun_resources.sh"'
phase.always_out_of_date = '1'
project.save

scheme = Xcodeproj::XCScheme.new
scheme.add_build_target(app)
scheme.add_test_target(unit)
scheme.add_test_target(ui)
scheme.set_launch_target(app)
scheme.launch_action.build_configuration = 'Debug-Local'
scheme.test_action.build_configuration = 'Debug-Local'
scheme.archive_action.build_configuration = 'Release'
scheme.save_as(project.path, 'QSMH5App', true)
integration = Xcodeproj::XCScheme.new
integration.add_build_target(app)
integration.set_launch_target(app)
integration.launch_action.build_configuration = 'Debug-Integration'
integration.archive_action.build_configuration = 'Release'
integration.save_as(project.path, 'QSMH5App-Integration', true)
puts '已生成 QSMH5App.xcodeproj 与共享 Scheme'
